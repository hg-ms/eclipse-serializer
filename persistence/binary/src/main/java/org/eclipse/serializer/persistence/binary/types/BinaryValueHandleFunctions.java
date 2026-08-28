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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;

import org.eclipse.serializer.memory.XMemory;
import org.eclipse.serializer.persistence.binary.exceptions.BinaryPersistenceException;

/**
 * Storers and setters that reach a field through a {@link VarHandle} rather than through its memory offset.
 * <p>
 * A field whose type is a value class may be laid out inside its owner rather than as a reference to a
 * separate instance. There is then no object reference at the field's offset, and reading one from there is
 * not merely inaccurate: depending on what the embedded content happens to be, it either yields the wrong
 * object or dereferences a value as if it were a pointer, which ends the process. Nothing in the public API
 * reports which fields are laid out that way, so every field of a value class is reached this way, whether
 * or not this JVM embedded that particular one.
 * <p>
 * A {@link VarHandle} is resolved by the JVM against the field's actual layout, so it returns the field's
 * value in either case. Offsets stay in use for every other field.
 *
 * @see BinaryValueFunctions
 */
public final class BinaryValueHandleFunctions
{
	///////////////////////////////////////////////////////////////////////////
	// static methods //
	///////////////////

	/**
	 * Resolves a {@link VarHandle} for the passed field.
	 *
	 * @param field the field to reach; must not be {@code null}.
	 *
	 * @return the handle for that field.
	 *
	 * @throws BinaryPersistenceException if the declaring class does not grant the required access.
	 */
	public static VarHandle provideVarHandle(final Field field)
	{
		try
		{
			return MethodHandles
				.privateLookupIn(field.getDeclaringClass(), MethodHandles.lookup())
				.unreflectVarHandle(field)
			;
		}
		catch(final IllegalAccessException e)
		{
			throw new BinaryPersistenceException(
				"Cannot access field " + field + ". Its module must open the declaring package.", e
			);
		}
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
		final VarHandle handle = provideVarHandle(field);

		return (source, sourceOffset, targetAddress, persister) ->
		{
			final Object value    = handle.get(source);
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
		final VarHandle handle = provideVarHandle(field);

		return (srcAddress, target, trgOffset, handler) ->
		{
			final long rawObjectId = XMemory.get_long(srcAddress);
			final long objectId    = switchByteOrder ? Long.reverseBytes(rawObjectId) : rawObjectId;

			handle.set(target, handler.lookupObject(objectId));

			return srcAddress + Binary.objectIdByteLength();
		};
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
