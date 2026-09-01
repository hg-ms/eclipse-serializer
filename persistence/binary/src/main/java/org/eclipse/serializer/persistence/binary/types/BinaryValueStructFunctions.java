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

import static org.eclipse.serializer.persistence.types.PersistenceTypeDescriptionMemberFieldValueStruct.NULL_MARKER_ABSENT;
import static org.eclipse.serializer.persistence.types.PersistenceTypeDescriptionMemberFieldValueStruct.NULL_MARKER_LENGTH;
import static org.eclipse.serializer.persistence.types.PersistenceTypeDescriptionMemberFieldValueStruct.NULL_MARKER_PRESENT;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;

import org.eclipse.serializer.collections.HashEnum;
import org.eclipse.serializer.collections.types.XGettingEnum;
import org.eclipse.serializer.collections.types.XGettingSequence;
import org.eclipse.serializer.memory.XMemory;
import org.eclipse.serializer.persistence.binary.exceptions.BinaryPersistenceException;
import org.eclipse.serializer.persistence.binary.types.BinaryValueHandleFunctions.FieldWriter;
import org.eclipse.serializer.persistence.types.PersistenceLoadHandler;
import org.eclipse.serializer.persistence.types.PersistenceStoreHandler;
import org.eclipse.serializer.persistence.types.PersistenceTypeDefinitionMemberField;
import org.eclipse.serializer.persistence.types.PersistenceTypeDefinitionMemberFieldValueStruct;
import org.eclipse.serializer.persistence.types.PersistenceTypeDescriptionMemberFieldValueStruct;
import org.eclipse.serializer.reflect.XReflect;

/**
 * Storer and setter for a field that is written into its owner's own binary form rather than referenced by
 * an object id.
 * <p>
 * The slot is a null marker byte followed by the inlined type's own persistent layout, so it is fixed-length
 * and its content is byte-identical to what that type's entity form would contain. A {@code null} field
 * writes the marker and zeroes the rest, which keeps the slot's length independent of its content.
 * <p>
 * Storing walks the inlined type's fields with the same per-field storers an entity of that type would use.
 * Loading cannot mirror that, because the fields of an identity-less instance cannot be written after
 * construction: the values are read into an argument array first and the instance is then constructed from
 * them, the same way its own entity form is reconstructed.
 */
public final class BinaryValueStructFunctions
{
	///////////////////////////////////////////////////////////////////////////
	// static methods //
	///////////////////

	/**
	 * Creates the storer writing an inlined field into its owner's binary form.
	 *
	 * @param ownerField      the owner field holding the inlined value; must not be {@code null}.
	 * @param members         the inlined layout's members, in persistent order.
	 * @param structLength    the slot's fixed length, including the null marker.
	 * @param switchByteOrder whether the persistent form uses the reversed byte order.
	 *
	 * @return the storer for the inlined field.
	 */
	public static BinaryValueStorer provideStorer(
		final Field                                                            ownerField     ,
		final XGettingSequence<? extends PersistenceTypeDefinitionMemberField> members        ,
		final long                                                             structLength   ,
		final boolean                                                          switchByteOrder
	)
	{
		final int                 count   = members.intSize();
		final BinaryValueStorer[] storers = new BinaryValueStorer[count];
		final long[]              offsets = new long[count];

		int i = 0;
		for(final PersistenceTypeDefinitionMemberField member : members)
		{
			final Field field = validateField(member);
			storers[i] = BinaryValueFunctions.getObjectValueStorer(field.getType(), false, switchByteOrder);
			offsets[i] = XMemory.objectFieldOffset(field);
			i++;
		}

		return new StructStorer(
			BinaryValueHandleFunctions.provideVarHandle(ownerField),
			storers,
			offsets,
			structLength
		);
	}

	/**
	 * Creates the setter reading an inlined field out of its owner's binary form.
	 *
	 * @param ownerField        the owner field holding the inlined value; must not be {@code null}.
	 * @param valueType         the inlined type; must not be {@code null}.
	 * @param members           the inlined layout's members, in persistent order.
	 * @param declarationOrder  the inlined type's persistable fields in declaration order, which is the order
	 *                          its constructor accepts them in.
	 * @param structLength      the slot's fixed length, including the null marker.
	 * @param switchByteOrder   whether the persistent form uses the reversed byte order.
	 *
	 * @return the setter for the inlined field.
	 */
	public static BinaryValueSetter provideSetter(
		final Field                                                            ownerField      ,
		final Class<?>                                                         valueType       ,
		final XGettingSequence<? extends PersistenceTypeDefinitionMemberField> members         ,
		final XGettingEnum<Field>                                              declarationOrder,
		final long                                                             structLength    ,
		final boolean                                                          switchByteOrder
	)
	{
		final int              count   = members.intSize();
		final StructReader[]   readers = new StructReader[count];
		final int[]            targets = new int[count];

		int i = 0;
		for(final PersistenceTypeDefinitionMemberField member : members)
		{
			final Field field = validateField(member);
			readers[i] = provideReader(field.getType(), switchByteOrder);

			/* The persistent order need not be the declaration order the constructor accepts, so every slot
			 * carries the argument index it belongs to rather than relying on the two coinciding.
			 */
			targets[i] = indexOf(declarationOrder, field);
			if(targets[i] < 0)
			{
				throw new BinaryPersistenceException(
					"Inlined field " + field + " is not among the persistable fields of " + valueType.getName()
				);
			}
			i++;
		}

		final MethodHandle constructor = BinaryHandlerGenericValueClass.resolveConstructor(
			valueType,
			BinaryHandlerGenericValueClass.toParameterTypes(declarationOrder),
			declarationOrder
		);

		return new StructSetter(
			BinaryValueHandleFunctions.provideFieldWriter(ownerField),
			valueType,
			readers,
			targets,
			constructor,
			declarationOrder.intSize(),
			structLength
		);
	}

	/**
	 * Creates the setter for an inlined field described by a legacy type definition, deriving the inlined
	 * type's declaration order from the type itself.
	 * <p>
	 * Only applicable while the described layout still matches the type's current one, which the caller has
	 * to establish: the constructor takes every field, so a described layout missing one could not be
	 * invoked, and one carrying an extra field would leave bytes unread.
	 *
	 * @param member          the inlined member as described by the legacy definition.
	 * @param switchByteOrder whether the persistent form uses the reversed byte order.
	 *
	 * @return the setter for the inlined field.
	 */
	public static BinaryValueSetter provideSetter(
		final PersistenceTypeDefinitionMemberFieldValueStruct member         ,
		final boolean                                        switchByteOrder
	)
	{
		final Class<?> valueType = member.type();
		if(valueType == null)
		{
			throw new BinaryPersistenceException(
				"Inlined field " + member.identifier() + " has no runtime type."
			);
		}

		final HashEnum<Field> declarationOrder = HashEnum.New();
		for(final Field field : valueType.getDeclaredFields())
		{
			if(!XReflect.isStatic(field) && isDescribed(member, field))
			{
				declarationOrder.add(field);
			}
		}

		if(declarationOrder.intSize() != member.members().intSize())
		{
			throw new BinaryPersistenceException(
				"Inlined layout of " + member.identifier() + " describes " + member.members().intSize()
				+ " fields, but " + valueType.getName() + " has " + declarationOrder.intSize() + " of them."
			);
		}

		return provideSetter(
			member.field()                  ,
			valueType                       ,
			member.members()                ,
			declarationOrder                ,
			member.persistentMinimumLength(),
			switchByteOrder
		);
	}

	private static boolean isDescribed(
		final PersistenceTypeDefinitionMemberFieldValueStruct member,
		final Field                                          field
	)
	{
		for(final PersistenceTypeDefinitionMemberField describedMember : member.members())
		{
			if(field.getName().equals(describedMember.name()))
			{
				return true;
			}
		}

		return false;
	}

	/**
	 * Creates the setter skipping an inlined field whose owner no longer has it.
	 *
	 * @param member the inlined member as described by the legacy definition.
	 *
	 * @return a setter advancing past the slot without reading it.
	 */
	public static BinaryValueSetter provideSkipper(
		final PersistenceTypeDescriptionMemberFieldValueStruct member
	)
	{
		final long structLength = member.persistentMinimumLength();

		return (srcAddress, target, trgOffset, handler) -> srcAddress + structLength;
	}

	private static Field validateField(final PersistenceTypeDefinitionMemberField member)
	{
		final Field field = member.field();
		if(field == null)
		{
			throw new BinaryPersistenceException(
				"Inlined layout member " + member.identifier() + " has no runtime field."
			);
		}

		return field;
	}

	private static int indexOf(final XGettingEnum<Field> fields, final Field field)
	{
		int i = 0;
		for(final Field f : fields)
		{
			if(f.equals(field))
			{
				return i;
			}
			i++;
		}

		return -1;
	}

	private static StructReader provideReader(final Class<?> type, final boolean switchByteOrder)
	{
		if(switchByteOrder)
		{
			return provideReaderReversed(type);
		}

		if(type == byte.class)
		{
			return (address, args, index) -> { args[index] = XMemory.get_byte(address); return Byte.BYTES; };
		}
		if(type == boolean.class)
		{
			return (address, args, index) -> { args[index] = XMemory.get_boolean(address); return Byte.BYTES; };
		}
		if(type == short.class)
		{
			return (address, args, index) -> { args[index] = XMemory.get_short(address); return Short.BYTES; };
		}
		if(type == char.class)
		{
			return (address, args, index) -> { args[index] = XMemory.get_char(address); return Character.BYTES; };
		}
		if(type == int.class)
		{
			return (address, args, index) -> { args[index] = XMemory.get_int(address); return Integer.BYTES; };
		}
		if(type == float.class)
		{
			return (address, args, index) -> { args[index] = XMemory.get_float(address); return Float.BYTES; };
		}
		if(type == long.class)
		{
			return (address, args, index) -> { args[index] = XMemory.get_long(address); return Long.BYTES; };
		}
		if(type == double.class)
		{
			return (address, args, index) -> { args[index] = XMemory.get_double(address); return Double.BYTES; };
		}

		throw new BinaryPersistenceException("Type cannot be inlined: " + type.getName());
	}

	private static StructReader provideReaderReversed(final Class<?> type)
	{
		if(type == byte.class)
		{
			return (address, args, index) -> { args[index] = XMemory.get_byte(address); return Byte.BYTES; };
		}
		if(type == boolean.class)
		{
			return (address, args, index) -> { args[index] = XMemory.get_boolean(address); return Byte.BYTES; };
		}
		if(type == short.class)
		{
			return (address, args, index) ->
			{
				args[index] = Short.reverseBytes(XMemory.get_short(address));
				return Short.BYTES;
			};
		}
		if(type == char.class)
		{
			return (address, args, index) ->
			{
				args[index] = Character.reverseBytes(XMemory.get_char(address));
				return Character.BYTES;
			};
		}
		if(type == int.class)
		{
			return (address, args, index) ->
			{
				args[index] = Integer.reverseBytes(XMemory.get_int(address));
				return Integer.BYTES;
			};
		}
		if(type == float.class)
		{
			return (address, args, index) ->
			{
				args[index] = Float.intBitsToFloat(Integer.reverseBytes(XMemory.get_int(address)));
				return Float.BYTES;
			};
		}
		if(type == long.class)
		{
			return (address, args, index) ->
			{
				args[index] = Long.reverseBytes(XMemory.get_long(address));
				return Long.BYTES;
			};
		}
		if(type == double.class)
		{
			return (address, args, index) ->
			{
				args[index] = Double.longBitsToDouble(Long.reverseBytes(XMemory.get_long(address)));
				return Double.BYTES;
			};
		}

		throw new BinaryPersistenceException("Type cannot be inlined: " + type.getName());
	}



	///////////////////////////////////////////////////////////////////////////
	// member types //
	/////////////////

	@FunctionalInterface
	private interface StructReader
	{
		/**
		 * Reads one inlined value into the constructor argument it belongs to.
		 *
		 * @return the number of bytes read.
		 */
		long readValue(long address, Object[] args, int index);
	}

	private static final class StructStorer implements BinaryValueStorer
	{
		private final VarHandle           ownerHandle ;
		private final BinaryValueStorer[] storers     ;
		private final long[]              offsets     ;
		private final long                structLength;

		StructStorer(
			final VarHandle           ownerHandle ,
			final BinaryValueStorer[] storers     ,
			final long[]              offsets     ,
			final long                structLength
		)
		{
			super();
			this.ownerHandle  = ownerHandle ;
			this.storers      = storers     ;
			this.offsets      = offsets     ;
			this.structLength = structLength;
		}

		@Override
		public long storeValueFromMemory(
			final Object                          source       ,
			final long                            sourceOffset ,
			final long                            targetAddress,
			final PersistenceStoreHandler<Binary> persister
		)
		{
			// reached through a handle: a value may be laid out inside its owner, with no reference at its offset
			final Object value = this.ownerHandle.get(source);
			if(value == null)
			{
				// zeroed rather than skipped, so the slot's content never depends on what was there before
				XMemory.fillMemory(targetAddress, this.structLength, (byte)0);
				return targetAddress + this.structLength;
			}

			XMemory.set_byte(targetAddress, NULL_MARKER_PRESENT);

			long address = targetAddress + NULL_MARKER_LENGTH;
			for(int i = 0; i < this.storers.length; i++)
			{
				address = this.storers[i].storeValueFromMemory(value, this.offsets[i], address, persister);
			}

			return address;
		}

	}

	private static final class StructSetter implements BinaryValueSetter
	{
		private final FieldWriter    ownerWriter  ;
		private final Class<?>       valueType    ;
		private final StructReader[] readers      ;
		private final int[]          targets      ;
		private final MethodHandle   constructor  ;
		private final int            argumentCount;
		private final long           structLength ;

		StructSetter(
			final FieldWriter    ownerWriter  ,
			final Class<?>       valueType    ,
			final StructReader[] readers      ,
			final int[]          targets      ,
			final MethodHandle   constructor  ,
			final int            argumentCount,
			final long           structLength
		)
		{
			super();
			this.ownerWriter   = ownerWriter  ;
			this.valueType     = valueType    ;
			this.readers       = readers      ;
			this.targets       = targets      ;
			this.constructor   = constructor  ;
			this.argumentCount = argumentCount;
			this.structLength  = structLength ;
		}

		@Override
		public long setValueToMemory(
			final long                   srcAddress,
			final Object                 target    ,
			final long                   trgOffset ,
			final PersistenceLoadHandler handler
		)
		{
			if(XMemory.get_byte(srcAddress) == NULL_MARKER_ABSENT)
			{
				this.ownerWriter.writeValue(target, null);
				return srcAddress + this.structLength;
			}

			final Object[] args = new Object[this.argumentCount];

			long address = srcAddress + NULL_MARKER_LENGTH;
			for(int i = 0; i < this.readers.length; i++)
			{
				address += this.readers[i].readValue(address, args, this.targets[i]);
			}

			this.ownerWriter.writeValue(target, this.createValue(args));

			return address;
		}

		private Object createValue(final Object[] args)
		{
			try
			{
				return (Object)this.constructor.invokeExact(args);
			}
			catch(final Error e)
			{
				throw e;
			}
			catch(final Throwable t)
			{
				throw new BinaryPersistenceException(
					"Could not construct inlined instance of " + this.valueType.getName(), t
				);
			}
		}

	}



	///////////////////////////////////////////////////////////////////////////
	// constructors //
	/////////////////

	private BinaryValueStructFunctions()
	{
		// static only
		throw new UnsupportedOperationException();
	}

}
