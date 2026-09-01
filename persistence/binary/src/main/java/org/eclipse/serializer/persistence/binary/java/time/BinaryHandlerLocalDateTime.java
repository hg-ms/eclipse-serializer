package org.eclipse.serializer.persistence.binary.java.time;

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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import org.eclipse.serializer.memory.XMemory;
import org.eclipse.serializer.persistence.binary.types.AbstractBinaryHandlerCustom;
import org.eclipse.serializer.persistence.binary.types.Binary;
import org.eclipse.serializer.persistence.types.PersistenceFunction;
import org.eclipse.serializer.persistence.types.PersistenceLoadHandler;
import org.eclipse.serializer.persistence.types.PersistenceReferenceLoader;
import org.eclipse.serializer.persistence.types.PersistenceStoreHandler;
import org.eclipse.serializer.reflect.XReflect;

/**
 * Handler for {@link LocalDateTime}, which holds its date and time parts in two references.
 * <p>
 * Where {@link LocalDateTime} is a value class, its instances cannot be created empty and populated
 * afterwards, and its constructor cannot be reached because its module does not open the package. It
 * is therefore built from its persisted parts through {@link LocalDateTime#of(LocalDate, LocalTime)}.
 * That resolves references in {@link #create}, which only a handler reporting
 * {@link #isValueClassType()} may do; the loader defers such a creation until they can be resolved.
 * <p>
 * Where it is an ordinary class, it keeps being created empty and populated, so that its instances
 * stay registered by identity as they are today.
 * <p>
 * The persisted form is byte-identical to the one the reflective handling produced, under the same
 * type and member names, so existing data is unaffected.
 */
public final class BinaryHandlerLocalDateTime extends AbstractBinaryHandlerCustom<LocalDateTime>
{
	///////////////////////////////////////////////////////////////////////////
	// constants //
	//////////////

	private static final long
		BINARY_OFFSET_DATE = 0                                                ,
		BINARY_OFFSET_TIME = BINARY_OFFSET_DATE + Binary.referenceBinaryLength(1),
		BINARY_LENGTH      = BINARY_OFFSET_TIME + Binary.referenceBinaryLength(1)
	;

	private static final String TYPE_NAME = LocalDateTime.class.getName();



	///////////////////////////////////////////////////////////////////////////
	// static methods //
	///////////////////

	public static BinaryHandlerLocalDateTime New()
	{
		return new BinaryHandlerLocalDateTime();
	}



	///////////////////////////////////////////////////////////////////////////
	// instance fields //
	////////////////////

	// only needed where the type is an ordinary class and its instances are populated after creation.
	private final long memoryOffsetDate;
	private final long memoryOffsetTime;



	///////////////////////////////////////////////////////////////////////////
	// constructors //
	/////////////////

	BinaryHandlerLocalDateTime()
	{
		super(
			LocalDateTime.class,
			CustomFields(
				/* Qualified with the declaring type, so this description stays the one the reflective
				 * handling produced and data written before this handler existed still matches.
				 */
				CustomField(LocalDate.class, TYPE_NAME, "date"),
				CustomField(LocalTime.class, TYPE_NAME, "time")
			)
		);

		final boolean populated = !this.isValueClassType();
		this.memoryOffsetDate = populated
			? XMemory.objectFieldOffset(XReflect.getAnyField(LocalDateTime.class, "date"))
			: -1
		;
		this.memoryOffsetTime = populated
			? XMemory.objectFieldOffset(XReflect.getAnyField(LocalDateTime.class, "time"))
			: -1
		;
	}



	///////////////////////////////////////////////////////////////////////////
	// override methods //
	/////////////////////

	@Override
	public void store(
		final Binary                          data    ,
		final LocalDateTime                   instance,
		final long                            objectId,
		final PersistenceStoreHandler<Binary> handler
	)
	{
		data.storeEntityHeader(BINARY_LENGTH, this.typeId(), objectId);
		data.store_long(BINARY_OFFSET_DATE, handler.apply(instance.toLocalDate()));
		data.store_long(BINARY_OFFSET_TIME, handler.apply(instance.toLocalTime()));
	}

	@Override
	public LocalDateTime create(final Binary data, final PersistenceLoadHandler handler)
	{
		if(!this.isValueClassType())
		{
			// created blank; the parts are set in #updateState
			return XMemory.instantiateBlank(LocalDateTime.class);
		}

		return LocalDateTime.of(
			(LocalDate)handler.lookupObject(data.read_long(BINARY_OFFSET_DATE)),
			(LocalTime)handler.lookupObject(data.read_long(BINARY_OFFSET_TIME))
		);
	}

	@Override
	public void updateState(
		final Binary                 data    ,
		final LocalDateTime          instance,
		final PersistenceLoadHandler handler
	)
	{
		if(this.isValueClassType())
		{
			// already complete: it was built from its content rather than populated
			return;
		}

		XMemory.setObject(instance, this.memoryOffsetDate, handler.lookupObject(data.read_long(BINARY_OFFSET_DATE)));
		XMemory.setObject(instance, this.memoryOffsetTime, handler.lookupObject(data.read_long(BINARY_OFFSET_TIME)));
	}

	@Override
	public void iterateInstanceReferences(final LocalDateTime instance, final PersistenceFunction iterator)
	{
		iterator.apply(instance.toLocalDate());
		iterator.apply(instance.toLocalTime());
	}

	@Override
	public void iterateLoadableReferences(final Binary data, final PersistenceReferenceLoader iterator)
	{
		iterator.acceptObjectId(data.read_long(BINARY_OFFSET_DATE));
		iterator.acceptObjectId(data.read_long(BINARY_OFFSET_TIME));
	}

	@Override
	public boolean hasPersistedReferences()
	{
		return true;
	}

	@Override
	public boolean hasVaryingPersistedLengthInstances()
	{
		return false;
	}

}
