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

import java.time.ZoneId;

import org.eclipse.serializer.persistence.binary.types.AbstractBinaryHandlerCustom;
import org.eclipse.serializer.persistence.binary.types.Binary;
import org.eclipse.serializer.persistence.types.PersistenceFunction;
import org.eclipse.serializer.persistence.types.PersistenceLoadHandler;
import org.eclipse.serializer.persistence.types.PersistenceReferenceLoader;
import org.eclipse.serializer.persistence.types.PersistenceStoreHandler;

/**
 * Handler for {@code java.time.ZoneRegion}, the package-private {@link ZoneId} implementation for
 * region-based zone ids. It holds nothing but its id; the zone rules are transient and re-resolved
 * from the id.
 * <p>
 * The instance is built completely through {@link ZoneId#of(String)}, with its creation deferred
 * until the id can be resolved. That matters to handlers building their own instances from a
 * resolved zone, e.g. for {@code ZonedDateTime}: a blank zone cannot answer for its rules, a
 * complete one can.
 * <p>
 * The persisted form is byte-identical to the one the reflective handling produced, under the same
 * type and member name, so existing data is unaffected.
 */
public final class BinaryHandlerZoneRegion extends AbstractBinaryHandlerCustom<ZoneId>
{
	///////////////////////////////////////////////////////////////////////////
	// constants //
	//////////////

	private static final long
		BINARY_OFFSET_ID = 0                                             ,
		BINARY_LENGTH    = BINARY_OFFSET_ID + Binary.referenceBinaryLength(1)
	;

	private static final String TYPE_NAME = "java.time.ZoneRegion";



	///////////////////////////////////////////////////////////////////////////
	// static methods //
	///////////////////

	@SuppressWarnings("unchecked")
	private static Class<ZoneId> handledType()
	{
		try
		{
			// the class is package-private, so it can only be resolved by name. Every instance is a ZoneId.
			return (Class<ZoneId>)Class.forName(TYPE_NAME);
		}
		catch(final ClassNotFoundException e)
		{
			throw new Error("JDK class " + TYPE_NAME + " not found.", e);
		}
	}

	public static BinaryHandlerZoneRegion New()
	{
		return new BinaryHandlerZoneRegion();
	}



	///////////////////////////////////////////////////////////////////////////
	// constructors //
	/////////////////

	BinaryHandlerZoneRegion()
	{
		super(
			handledType(),
			CustomFields(
				/* Qualified with the declaring type, so this description stays the one the reflective
				 * handling produced and data written before this handler existed still matches.
				 */
				CustomField(String.class, TYPE_NAME, "id")
			)
		);
	}



	///////////////////////////////////////////////////////////////////////////
	// override methods //
	/////////////////////

	@Override
	public boolean isCreationDeferred()
	{
		// built from its resolved id instead of being populated, see the type comment.
		return true;
	}

	@Override
	public void store(
		final Binary                          data    ,
		final ZoneId                          instance,
		final long                            objectId,
		final PersistenceStoreHandler<Binary> handler
	)
	{
		data.storeEntityHeader(BINARY_LENGTH, this.typeId(), objectId);
		data.store_long(BINARY_OFFSET_ID, handler.apply(instance.getId()));
	}

	@Override
	public ZoneId create(final Binary data, final PersistenceLoadHandler handler)
	{
		return ZoneId.of(
			(String)handler.lookupObject(data.read_long(BINARY_OFFSET_ID))
		);
	}

	@Override
	public void updateState(final Binary data, final ZoneId instance, final PersistenceLoadHandler handler)
	{
		// already complete: it was built from its content rather than populated
	}

	@Override
	public void iterateInstanceReferences(final ZoneId instance, final PersistenceFunction iterator)
	{
		iterator.apply(instance.getId());
	}

	@Override
	public void iterateLoadableReferences(final Binary data, final PersistenceReferenceLoader iterator)
	{
		iterator.acceptObjectId(data.read_long(BINARY_OFFSET_ID));
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
