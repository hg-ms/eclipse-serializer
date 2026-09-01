package org.eclipse.serializer.persistence.binary.types;

/*-
 * #%L
 * Eclipse Serializer Persistence Binary
 * %%
 * Copyright (C) 2023 - 2026 MicroStream Software
 * %%
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 * #L%
 */

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.eclipse.serializer.memory.XMemory;
import org.eclipse.serializer.persistence.binary.exceptions.BinaryPersistenceException;
import org.eclipse.serializer.reflect.XReflect;

/**
 * Storers and setters that reach a field through a handle rather than through its memory offset.
 * <p>
 * A field whose type is a value class may be laid out inside its owner rather than as a reference to a
 * separate instance. There is then no object reference at the field's offset, and reading one from there is
 * not merely inaccurate: depending on what the embedded content happens to be, it either yields the wrong
 * object or dereferences a value as if it were a pointer, which ends the process. Nothing in the public API
 * reports which fields are laid out that way, so every field of a value class is reached this way, whether
 * or not this JVM embedded that particular one.
 * <p>
 * Both directions go through the accessible field's {@link MethodHandle}, which the JVM resolves against
 * the field's actual layout, which writes final fields (unlike a {@link java.lang.invoke.VarHandle}), and
 * whose signature can be pre-adapted so every invocation is exact. The one exception is a record's fields,
 * which accept no handle write at all and are written through the same internal accessor that populates
 * their other fields, made layout-aware. Offsets stay in use for every field that is not value-typed.
 *
 * @see BinaryValueFunctions
 */
public final class BinaryValueHandleFunctions
{
	///////////////////////////////////////////////////////////////////////////
	// static methods //
	///////////////////

	/**
	 * Resolves the reader for the passed field, resolved by the JVM against the field's actual layout.
	 *
	 * @param field the field to read; must not be {@code null}.
	 *
	 * @return the reader for that field.
	 *
	 * @throws BinaryPersistenceException if the declaring class does not grant the required access.
	 */
	public static FieldReader provideFieldReader(final Field field)
	{
		final MethodHandle getter;
		try
		{
			// pre-adapted so every read is an exact invocation instead of an adapted one.
			getter = MethodHandles.lookup()
				.unreflectGetter(XReflect.setAccessible(field))
				.asType(MethodType.methodType(Object.class, Object.class))
			;
		}
		catch(final IllegalAccessException e)
		{
			throw new BinaryPersistenceException(
				"Cannot read field " + field + ". Its module must open the declaring package.", e
			);
		}

		return owner ->
		{
			try
			{
				return getter.invokeExact(owner);
			}
			catch(final RuntimeException | Error e)
			{
				throw e;
			}
			catch(final Throwable t)
			{
				throw new BinaryPersistenceException("Failed to read field " + field + ".", t);
			}
		};
	}

	/**
	 * Resolves the writer for the passed field, capable of writing a final field and resolved by the
	 * JVM against the field's actual layout.
	 *
	 * @param field the field to write; must not be {@code null}.
	 *
	 * @return the writer for that field.
	 *
	 * @throws BinaryPersistenceException if the declaring class does not grant the required access.
	 */
	public static FieldWriter provideFieldWriter(final Field field)
	{
		if(field.getDeclaringClass().isRecord())
		{
			/* A record's fields refuse every handle or reflective write, however accessible. They are
			 * populated through the internal accessor like every other of the record's fields, with the
			 * one difference that a value field may be laid out flat inside the record, where a plain
			 * reference write would destroy the owner's memory instead of setting the field.
			 */
			return InternalUnsafeFlatAccess.provideFieldWriter(field);
		}

		final MethodHandle setter;
		try
		{
			// the accessible flag is what permits writing a final field, unlike for a VarHandle.
			setter = MethodHandles.lookup()
				.unreflectSetter(XReflect.setAccessible(field))
				.asType(MethodType.methodType(void.class, Object.class, Object.class))
			;
		}
		catch(final IllegalAccessException e)
		{
			throw new BinaryPersistenceException(
				"Cannot write field " + field + ". Its module must open the declaring package.", e
			);
		}

		return (owner, value) ->
		{
			try
			{
				setter.invokeExact(owner, value);
			}
			catch(final RuntimeException | Error e)
			{
				throw e;
			}
			catch(final Throwable t)
			{
				throw new BinaryPersistenceException("Failed to write field " + field + ".", t);
			}
		};
	}

	/**
	 * Creates the storer writing the object id of a value class field's value.
	 *
	 * @param field           the field to read; must not be {@code null}.
	 * @param isEager         whether the referenced value is stored eagerly.
	 * @param switchByteOrder whether the persistent form uses the reversed byte order.
	 *
	 * @return the storer for the field.
	 */
	public static BinaryValueStorer provideReferenceStorer(
		final Field   field          ,
		final boolean isEager        ,
		final boolean switchByteOrder
	)
	{
		final FieldReader reader = provideFieldReader(field);

		return (source, sourceOffset, targetAddress, persister) ->
		{
			final Object value    = reader.readValue(source);
			final long   objectId = isEager
				? persister.applyEager(value)
				: persister.apply(value)
			;

			XMemory.set_long(targetAddress, switchByteOrder ? Long.reverseBytes(objectId) : objectId);

			return targetAddress + Binary.objectIdByteLength();
		};
	}

	/**
	 * Creates the setter resolving an object id into a value class field.
	 *
	 * @param field           the field to write; must not be {@code null}.
	 * @param switchByteOrder whether the persistent form uses the reversed byte order.
	 *
	 * @return the setter for the field.
	 */
	public static BinaryValueSetter provideReferenceSetter(
		final Field   field          ,
		final boolean switchByteOrder
	)
	{
		final FieldWriter writer = provideFieldWriter(field);

		return (srcAddress, target, trgOffset, handler) ->
		{
			final long rawObjectId = XMemory.get_long(srcAddress);
			final long objectId    = switchByteOrder ? Long.reverseBytes(rawObjectId) : rawObjectId;

			writer.writeValue(target, handler.lookupObject(objectId));

			return srcAddress + Binary.objectIdByteLength();
		};
	}



	///////////////////////////////////////////////////////////////////////////
	// member types //
	/////////////////

	/**
	 * Reads one field's value from an owner instance.
	 *
	 * @see BinaryValueHandleFunctions#provideFieldReader(Field)
	 */
	@FunctionalInterface
	public interface FieldReader
	{
		/**
		 * Reads this reader's field from the passed owner.
		 *
		 * @param owner the instance to read from.
		 *
		 * @return the field's value, may be {@code null}.
		 */
		public Object readValue(Object owner);
	}

	/**
	 * Writes one field's value into an owner instance.
	 *
	 * @see BinaryValueHandleFunctions#provideFieldWriter(Field)
	 */
	@FunctionalInterface
	public interface FieldWriter
	{
		/**
		 * Writes the passed value into this writer's field of the passed owner.
		 *
		 * @param owner the instance to write into.
		 * @param value the value to write, may be {@code null}.
		 */
		public void writeValue(Object owner, Object value);
	}

	/**
	 * Access to the internal accessor's layout-aware field writing, resolved reflectively since it is
	 * compiled against a JDK baseline that does not have those methods. Only ever needed for a record's
	 * value class fields, so being unresolvable is not reported before such a field is actually written.
	 */
	private static final class InternalUnsafeFlatAccess
	{
		// all null if the internal accessor is unavailable, see #resolve and #provideFieldWriter.
		private static final Object UNSAFE               ;
		private static final Method OBJECT_FIELD_OFFSET  ;
		private static final Method IS_FLAT_FIELD        ;
		private static final Method FIELD_LAYOUT         ;
		private static final Method PUT_FLAT_VALUE       ;
		private static final Method PUT_REFERENCE        ;

		static
		{
			Object unsafe            = null;
			Method objectFieldOffset = null;
			Method isFlatField       = null;
			Method fieldLayout       = null;
			Method putFlatValue      = null;
			Method putReference      = null;
			try
			{
				final Class<?> unsafeClass = Class.forName("jdk.internal.misc.Unsafe");
				unsafe            = unsafeClass.getMethod("getUnsafe").invoke(null);
				objectFieldOffset = unsafeClass.getMethod("objectFieldOffset", Field.class);
				isFlatField       = unsafeClass.getMethod("isFlatField", Field.class);
				fieldLayout       = unsafeClass.getMethod("fieldLayout", Field.class);
				putFlatValue      = unsafeClass.getMethod(
					"putFlatValue", Object.class, long.class, int.class, Class.class, Object.class
				);
				putReference      = unsafeClass.getMethod("putReference", Object.class, long.class, Object.class);
			}
			catch(final ReflectiveOperationException e)
			{
				// left unresolved; reported when a field actually needs it.
			}
			UNSAFE              = unsafe           ;
			OBJECT_FIELD_OFFSET = objectFieldOffset;
			IS_FLAT_FIELD       = isFlatField      ;
			FIELD_LAYOUT        = fieldLayout      ;
			PUT_FLAT_VALUE      = putFlatValue     ;
			PUT_REFERENCE       = putReference     ;
		}

		static FieldWriter provideFieldWriter(final Field field)
		{
			if(UNSAFE == null)
			{
				throw new BinaryPersistenceException(
					"Cannot write field " + field + ": no access to \"jdk.internal.misc.Unsafe\"."
					+ " Please start the VM with --add-exports java.base/jdk.internal.misc=ALL-UNNAMED"
				);
			}

			final long offset = (Long)invoke(OBJECT_FIELD_OFFSET, field, field);

			if(!(Boolean)invoke(IS_FLAT_FIELD, field, field))
			{
				// not laid out inside its owner, so the field holds a plain reference.
				return (owner, value) -> invoke(PUT_REFERENCE, field, owner, offset, value);
			}

			final int      layout = (Integer)invoke(FIELD_LAYOUT, field, field);
			final Class<?> type   = field.getType();

			return (owner, value) -> invoke(PUT_FLAT_VALUE, field, owner, offset, layout, type, value);
		}

		private static Object invoke(final Method method, final Field field, final Object... arguments)
		{
			try
			{
				return method.invoke(UNSAFE, arguments);
			}
			catch(final InvocationTargetException e)
			{
				final Throwable cause = e.getCause();
				if(cause instanceof RuntimeException)
				{
					throw (RuntimeException)cause;
				}
				if(cause instanceof Error)
				{
					throw (Error)cause;
				}
				throw new BinaryPersistenceException("Failed to write field " + field + ".", cause);
			}
			catch(final IllegalAccessException e)
			{
				throw new BinaryPersistenceException("Failed to write field " + field + ".", e);
			}
		}
	}



	///////////////////////////////////////////////////////////////////////////
	// constructors //
	/////////////////

	private BinaryValueHandleFunctions()
	{
		// static only
		throw new UnsupportedOperationException();
	}

}
