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

import java.time.LocalTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;

import org.eclipse.serializer.memory.XMemory;
import org.eclipse.serializer.persistence.binary.types.AbstractBinaryHandlerCustom;
import org.eclipse.serializer.persistence.binary.types.Binary;
import org.eclipse.serializer.persistence.types.PersistenceFunction;
import org.eclipse.serializer.persistence.types.PersistenceLoadHandler;
import org.eclipse.serializer.persistence.types.PersistenceReferenceLoader;
import org.eclipse.serializer.persistence.types.PersistenceStoreHandler;
import org.eclipse.serializer.reflect.XReflect;

/**
 * Handler for {@link OffsetTime}, which holds its time and offset in two references.
 * <p>
 * Where {@link OffsetTime} is a value class, its instances cannot be created empty and populated
 * afterwards, and its constructor cannot be reached because its module does not open the package. It
 * is therefore built from its persisted parts through {@link OffsetTime#of(LocalTime, ZoneOffset)}.
 * That resolves references in {@link #create}, which only a handler reporting
 * {@link #isValueClassType()} may do; the loader defers such a creation until they can be resolved.
 * <p>
 * Where it is an ordinary class, it keeps being created empty and populated, so that its instances
 * stay registered by identity as they are today.
 * <p>
 * The persisted form is byte-identical to the one the reflective handling produced, under the same
 * type and member names, so existing data is unaffected.
 */
public final class BinaryHandlerOffsetTime extends AbstractBinaryHandlerCustom<OffsetTime>
{
	///////////////////////////////////////////////////////////////////////////
	// constants //
	//////////////

	private static final long
		BINARY_OFFSET_TIME   = 0                                                  ,
		BINARY_OFFSET_OFFSET = BINARY_OFFSET_TIME   + Binary.referenceBinaryLength(1),
		BINARY_LENGTH        = BINARY_OFFSET_OFFSET + Binary.referenceBinaryLength(1)
	;

	private static final String TYPE_NAME = OffsetTime.class.getName();



	///////////////////////////////////////////////////////////////////////////
	// static methods //
	///////////////////

	public static BinaryHandlerOffsetTime New()
	{
		return new BinaryHandlerOffsetTime();
	}



	///////////////////////////////////////////////////////////////////////////
	// instance fields //
	////////////////////

	// only needed where the type is an ordinary class and its instances are populated after creation.
	private final long memoryOffsetTime  ;
	private final long memoryOffsetOffset;



	///////////////////////////////////////////////////////////////////////////
	// constructors //
	/////////////////

	BinaryHandlerOffsetTime()
	{
		super(
			OffsetTime.class,
			CustomFields(
				/* Qualified with the declaring type, so this description stays the one the reflective
				 * handling produced and data written before this handler existed still matches.
				 */
				CustomField(LocalTime.class , TYPE_NAME, "time"  ),
				CustomField(ZoneOffset.class, TYPE_NAME, "offset")
			)
		);

		final boolean populated = !this.isValueClassType();
		this.memoryOffsetTime   = populated
			? XMemory.objectFieldOffset(XReflect.getAnyField(OffsetTime.class, "time"))
			: -1
		;
		this.memoryOffsetOffset = populated
			? XMemory.objectFieldOffset(XReflect.getAnyField(OffsetTime.class, "offset"))
			: -1
		;
	}



	///////////////////////////////////////////////////////////////////////////
	// override methods //
	/////////////////////

	@Override
	public void store(
		final Binary                          data    ,
		final OffsetTime                      instance,
		final long                            objectId,
		final PersistenceStoreHandler<Binary> handler
	)
	{
		data.storeEntityHeader(BINARY_LENGTH, this.typeId(), objectId);
		data.store_long(BINARY_OFFSET_TIME  , handler.apply(instance.toLocalTime()));
		data.store_long(BINARY_OFFSET_OFFSET, handler.apply(instance.getOffset())  );
	}

	@Override
	public OffsetTime create(final Binary data, final PersistenceLoadHandler handler)
	{
		if(!this.isValueClassType())
		{
			// created blank; the parts are set in #updateState
			return XMemory.instantiateBlank(OffsetTime.class);
		}

		return OffsetTime.of(
			(LocalTime) handler.lookupObject(data.read_long(BINARY_OFFSET_TIME))  ,
			(ZoneOffset)handler.lookupObject(data.read_long(BINARY_OFFSET_OFFSET))
		);
	}

	@Override
	public void updateState(
		final Binary                 data    ,
		final OffsetTime             instance,
		final PersistenceLoadHandler handler
	)
	{
		if(this.isValueClassType())
		{
			// already complete: it was built from its content rather than populated
			return;
		}

		XMemory.setObject(instance, this.memoryOffsetTime  , handler.lookupObject(data.read_long(BINARY_OFFSET_TIME))  );
		XMemory.setObject(instance, this.memoryOffsetOffset, handler.lookupObject(data.read_long(BINARY_OFFSET_OFFSET)));
	}

	@Override
	public void iterateInstanceReferences(final OffsetTime instance, final PersistenceFunction iterator)
	{
		iterator.apply(instance.toLocalTime());
		iterator.apply(instance.getOffset());
	}

	@Override
	public void iterateLoadableReferences(final Binary data, final PersistenceReferenceLoader iterator)
	{
		iterator.acceptObjectId(data.read_long(BINARY_OFFSET_TIME));
		iterator.acceptObjectId(data.read_long(BINARY_OFFSET_OFFSET));
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
