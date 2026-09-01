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

import java.time.Instant;

import org.eclipse.serializer.memory.XMemory;
import org.eclipse.serializer.persistence.binary.types.AbstractBinaryHandlerCustomNonReferentialFixedLength;
import org.eclipse.serializer.persistence.binary.types.Binary;
import org.eclipse.serializer.persistence.types.PersistenceLoadHandler;
import org.eclipse.serializer.persistence.types.PersistenceStoreHandler;
import org.eclipse.serializer.reflect.XReflect;

/**
 * Handler for {@link Instant}.
 * <p>
 * Where {@link Instant} is a value class, its instances cannot be created empty and populated
 * afterwards: the population write is not reliably visible on an identity-less instance. It is
 * therefore built from its persisted values through {@link Instant#ofEpochSecond(long, long)},
 * which reproduces the state exactly for a persisted nano value, always within
 * {@code [0, 999_999_999]}.
 * <p>
 * Where it is an ordinary class, it keeps being created empty and populated, preserving the
 * behavior the reflective handling had, updating an already registered instance included.
 * <p>
 * The persisted form is byte-identical to the one the reflective handling produced, under the same
 * type and member names, so existing data is unaffected.
 */
public final class BinaryHandlerInstant extends AbstractBinaryHandlerCustomNonReferentialFixedLength<Instant>
{
	///////////////////////////////////////////////////////////////////////////
	// constants //
	//////////////

	private static final long
		BINARY_OFFSET_SECONDS = 0                                     ,
		BINARY_OFFSET_NANOS   = BINARY_OFFSET_SECONDS + Long.BYTES    ,
		BINARY_LENGTH         = BINARY_OFFSET_NANOS   + Integer.BYTES
	;



	///////////////////////////////////////////////////////////////////////////
	// static methods //
	///////////////////

	public static BinaryHandlerInstant New()
	{
		return new BinaryHandlerInstant();
	}



	///////////////////////////////////////////////////////////////////////////
	// instance fields //
	////////////////////

	// only needed where the type is an ordinary class and its instances are populated after creation.
	private final long memoryOffsetSeconds;
	private final long memoryOffsetNanos  ;



	///////////////////////////////////////////////////////////////////////////
	// constructors //
	/////////////////

	BinaryHandlerInstant()
	{
		super(
			Instant.class,
			CustomFields(
				/* Qualified with the declaring type, so this description stays the one the reflective
				 * handling produced and data written before this handler existed still matches.
				 */
				CustomField(long.class, Instant.class.getName(), "seconds"),
				CustomField(int.class , Instant.class.getName(), "nanos"  )
			)
		);

		final boolean populated = !this.isValueClassType();
		this.memoryOffsetSeconds = populated
			? XMemory.objectFieldOffset(XReflect.getAnyField(Instant.class, "seconds"))
			: -1
		;
		this.memoryOffsetNanos   = populated
			? XMemory.objectFieldOffset(XReflect.getAnyField(Instant.class, "nanos"))
			: -1
		;
	}



	///////////////////////////////////////////////////////////////////////////
	// override methods //
	/////////////////////

	@Override
	public void store(
		final Binary                          data    ,
		final Instant                         instance,
		final long                            objectId,
		final PersistenceStoreHandler<Binary> handler
	)
	{
		data.storeEntityHeader(BINARY_LENGTH, this.typeId(), objectId);
		data.store_long(BINARY_OFFSET_SECONDS, instance.getEpochSecond());
		data.store_int (BINARY_OFFSET_NANOS  , instance.getNano()       );
	}

	@Override
	public Instant create(final Binary data, final PersistenceLoadHandler handler)
	{
		if(!this.isValueClassType())
		{
			// created blank; the values are set in #updateState
			return XMemory.instantiateBlank(Instant.class);
		}

		return Instant.ofEpochSecond(
			data.read_long(BINARY_OFFSET_SECONDS),
			data.read_int (BINARY_OFFSET_NANOS)
		);
	}

	@Override
	public void updateState(final Binary data, final Instant instance, final PersistenceLoadHandler handler)
	{
		if(this.isValueClassType())
		{
			// already complete: it was built from its content rather than populated
			return;
		}

		XMemory.set_long(instance, this.memoryOffsetSeconds, data.read_long(BINARY_OFFSET_SECONDS));
		XMemory.set_int (instance, this.memoryOffsetNanos  , data.read_int (BINARY_OFFSET_NANOS)  );
	}

}
