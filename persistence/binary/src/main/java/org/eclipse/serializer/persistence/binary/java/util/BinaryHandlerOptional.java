package org.eclipse.serializer.persistence.binary.java.util;

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

import java.util.Optional;

import org.eclipse.serializer.memory.XMemory;
import org.eclipse.serializer.persistence.binary.types.AbstractBinaryHandlerCustom;
import org.eclipse.serializer.persistence.binary.types.Binary;
import org.eclipse.serializer.persistence.types.PersistenceFunction;
import org.eclipse.serializer.persistence.types.PersistenceLoadHandler;
import org.eclipse.serializer.persistence.types.PersistenceReferenceLoader;
import org.eclipse.serializer.persistence.types.PersistenceStoreHandler;
import org.eclipse.serializer.reflect.XReflect;

/**
 * Handler for {@link Optional}, which holds its content in a single reference.
 * <p>
 * Where {@link Optional} is a value class, its instances cannot be created empty and populated
 * afterwards, and its constructor cannot be reached because its module does not open the package. It
 * is therefore built from its persisted content through {@link Optional#ofNullable(Object)}, which
 * needs no access at all. That resolves a reference in {@link #create}, which only a handler
 * reporting {@link #isValueClassType()} may do; the loader defers such a creation until the
 * reference can be resolved.
 * <p>
 * Where it is an ordinary class, it keeps being created empty and populated, so that its instances
 * stay registered by identity as they are today.
 * <p>
 * An absent value needs no marker of its own: a null reference reads back as
 * {@link Optional#empty()}, which is what {@link Optional#ofNullable(Object)} answers for it. The
 * persisted form is the same single reference the reflective handling produced, under the same type
 * and member name, so existing data is unaffected.
 */
public final class BinaryHandlerOptional extends AbstractBinaryHandlerCustom<Optional<?>>
{
	///////////////////////////////////////////////////////////////////////////
	// constants //
	//////////////

	private static final long
		BINARY_OFFSET_VALUE = 0                                                ,
		BINARY_LENGTH       = BINARY_OFFSET_VALUE + Binary.referenceBinaryLength(1)
	;

	private static final String FIELD_NAME_VALUE = "value";



	///////////////////////////////////////////////////////////////////////////
	// static methods //
	///////////////////

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static Class<Optional<?>> handledType()
	{
		// no way to get ".class" of a parameterized type otherwise
		return (Class)Optional.class;
	}

	public static BinaryHandlerOptional New()
	{
		return new BinaryHandlerOptional();
	}



	///////////////////////////////////////////////////////////////////////////
	// instance fields //
	////////////////////

	/* Only needed where Optional is an ordinary class and its instances are populated after creation.
	 * Where it is a value class they are built from their content instead, so there is nothing to write.
	 */
	private final long memoryOffsetValue;



	///////////////////////////////////////////////////////////////////////////
	// constructors //
	/////////////////

	BinaryHandlerOptional()
	{
		super(
			handledType(),
			CustomFields(
				/* Qualified with the declaring type, so this description stays the one the reflective
				 * handling produced and data written before this handler existed still matches.
				 */
				CustomField(Object.class, Optional.class.getName(), FIELD_NAME_VALUE)
			)
		);

		this.memoryOffsetValue = this.isValueClassType()
			? -1
			: XMemory.objectFieldOffset(XReflect.getAnyField(Optional.class, FIELD_NAME_VALUE))
		;
	}



	///////////////////////////////////////////////////////////////////////////
	// override methods //
	/////////////////////

	@Override
	public void store(
		final Binary                          data    ,
		final Optional<?>                     instance,
		final long                            objectId,
		final PersistenceStoreHandler<Binary> handler
	)
	{
		data.storeEntityHeader(BINARY_LENGTH, this.typeId(), objectId);
		data.store_long(BINARY_OFFSET_VALUE, handler.apply(instance.orElse(null)));
	}

	@Override
	public Optional<?> create(final Binary data, final PersistenceLoadHandler handler)
	{
		if(!this.isValueClassType())
		{
			// an all-zeroes instance is Optional.empty; the content is set in #updateState
			return XMemory.instantiateBlank(handledType());
		}

		return Optional.ofNullable(handler.lookupObject(data.read_long(BINARY_OFFSET_VALUE)));
	}

	@Override
	public void updateState(
		final Binary                 data    ,
		final Optional<?>            instance,
		final PersistenceLoadHandler handler
	)
	{
		if(this.isValueClassType())
		{
			// already complete: it was built from its content rather than populated
			return;
		}

		XMemory.setObject(
			instance,
			this.memoryOffsetValue,
			handler.lookupObject(data.read_long(BINARY_OFFSET_VALUE))
		);
	}

	@Override
	public void iterateInstanceReferences(final Optional<?> instance, final PersistenceFunction iterator)
	{
		iterator.apply(instance.orElse(null));
	}

	@Override
	public void iterateLoadableReferences(final Binary data, final PersistenceReferenceLoader iterator)
	{
		iterator.acceptObjectId(data.read_long(BINARY_OFFSET_VALUE));
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
