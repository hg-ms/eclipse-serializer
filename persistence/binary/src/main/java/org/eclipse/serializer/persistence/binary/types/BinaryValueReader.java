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

import org.eclipse.serializer.persistence.binary.exceptions.BinaryPersistenceException;
import org.eclipse.serializer.persistence.types.PersistenceLoadHandler;

/**
 * Reads a single persisted field value and returns it, as opposed to {@link BinaryValueSetter},
 * which writes it directly into an instance's memory.
 * <p>
 * This indirection is required wherever the values must exist <i>before</i> the instance does, i.e.
 * for constructor-based instantiation.
 *
 * @see BinaryHandlerGenericValueClass
 */
@FunctionalInterface
public interface BinaryValueReader
{
	/**
	 * Reads the value located at {@code offset} in the passed entity data.
	 *
	 * @param data    the entity data to read from.
	 * @param offset  the value's offset in the entity's content.
	 * @param handler the load handler used to resolve references.
	 *
	 * @return the read value, boxed if primitive.
	 */
	public Object readValue(Binary data, long offset, PersistenceLoadHandler handler);


	/**
	 * Provides the reader matching the passed field type.
	 *
	 * @param type            the field type whose value is to be read.
	 * @param switchByteOrder whether persisted values use a non-native byte order.
	 *
	 * @return the matching reader.
	 */
	public static BinaryValueReader provideReader(final Class<?> type, final boolean switchByteOrder)
	{
		if(!type.isPrimitive())
		{
			// object ids are read as a whole, so only their byte order has to be corrected.
			return switchByteOrder
				? (data, offset, handler) -> handler.lookupObject(Long.reverseBytes(data.read_long(offset)))
				: (data, offset, handler) -> handler.lookupObject(data.read_long(offset))
			;
		}

		if(type == int.class)
		{
			return switchByteOrder
				? (data, offset, handler) -> Integer.valueOf(Integer.reverseBytes(data.read_int(offset)))
				: (data, offset, handler) -> Integer.valueOf(data.read_int(offset))
			;
		}
		if(type == long.class)
		{
			return switchByteOrder
				? (data, offset, handler) -> Long.valueOf(Long.reverseBytes(data.read_long(offset)))
				: (data, offset, handler) -> Long.valueOf(data.read_long(offset))
			;
		}
		if(type == boolean.class)
		{
			// a single byte has no byte order.
			return (data, offset, handler) -> Boolean.valueOf(data.read_boolean(offset));
		}
		if(type == byte.class)
		{
			// a single byte has no byte order.
			return (data, offset, handler) -> Byte.valueOf(data.read_byte(offset));
		}
		if(type == double.class)
		{
			return switchByteOrder
				? (data, offset, handler) -> Double.valueOf(
					Double.longBitsToDouble(Long.reverseBytes(Double.doubleToRawLongBits(data.read_double(offset)))))
				: (data, offset, handler) -> Double.valueOf(data.read_double(offset))
			;
		}
		if(type == float.class)
		{
			return switchByteOrder
				? (data, offset, handler) -> Float.valueOf(
					Float.intBitsToFloat(Integer.reverseBytes(Float.floatToRawIntBits(data.read_float(offset)))))
				: (data, offset, handler) -> Float.valueOf(data.read_float(offset))
			;
		}
		if(type == char.class)
		{
			return switchByteOrder
				? (data, offset, handler) -> Character.valueOf(Character.reverseBytes(data.read_char(offset)))
				: (data, offset, handler) -> Character.valueOf(data.read_char(offset))
			;
		}
		if(type == short.class)
		{
			return switchByteOrder
				? (data, offset, handler) -> Short.valueOf(Short.reverseBytes(data.read_short(offset)))
				: (data, offset, handler) -> Short.valueOf(data.read_short(offset))
			;
		}

		throw new BinaryPersistenceException("Unhandled primitive type: " + type.getName());
	}

}
