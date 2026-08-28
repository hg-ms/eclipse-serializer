package org.eclipse.serializer.persistence.types;

/*-
 * #%L
 * Eclipse Serializer Persistence
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

import static org.eclipse.serializer.util.X.notNull;

import org.eclipse.serializer.collections.types.XGettingSequence;
import org.eclipse.serializer.collections.types.XImmutableSequence;
import org.eclipse.serializer.persistence.exceptions.PersistenceException;

/**
 * A field whose value is not referenced by an object id but written into the owner's own binary form,
 * described by a nested sequence of members that mirrors the field type's own persistent layout.
 * <p>
 * The persistent form is a null marker byte followed by that layout, so the slot is fixed-length and the
 * content after the marker is byte-identical to what the field type's own entity form would contain. A
 * marker of {@link #NULL_MARKER_ABSENT} states that the field is {@code null}; the remaining bytes of the
 * slot are then zero and carry no meaning.
 * <p>
 * Inlining a field this way removes one entity, and with it one object id, per owner. It is only applicable
 * to a field whose declared type is statically known to be exactly the type described here, since the
 * persistent form carries no type information of its own.
 *
 * @see PersistenceTypeDescriptionMemberFieldReflective
 */
public interface PersistenceTypeDescriptionMemberFieldValueStruct
extends PersistenceTypeDescriptionMemberFieldReflective
{
	///////////////////////////////////////////////////////////////////////////
	// constants //
	//////////////

	/**
	 * The marker value stating that the inlined field is {@code null}.
	 */
	public static final byte NULL_MARKER_ABSENT = 0;

	/**
	 * The marker value stating that the inlined field holds a value.
	 */
	public static final byte NULL_MARKER_PRESENT = 1;

	/**
	 * The length of the null marker preceding the inlined content.
	 */
	public static final long NULL_MARKER_LENGTH = Byte.BYTES;



	///////////////////////////////////////////////////////////////////////////
	// static methods //
	///////////////////

	/**
	 * Calculates the fixed persistent length of an inlined slot holding the passed members: the null marker
	 * plus every member's own fixed length.
	 *
	 * @param members the members describing the inlined layout.
	 *
	 * @return the fixed persistent length of the slot.
	 *
	 * @throws PersistenceException if a member is not fixed-length, which would make the slot unskippable.
	 */
	public static long calculateStructLength(
		final XGettingSequence<? extends PersistenceTypeDescriptionMember> members
	)
	{
		long length = NULL_MARKER_LENGTH;

		for(final PersistenceTypeDescriptionMember member : members)
		{
			if(!member.isFixedLength())
			{
				throw new PersistenceException(
					"Variable length member " + member.identifier() + " cannot be inlined."
				);
			}
			length += member.persistentMinimumLength();
		}

		return length;
	}



	///////////////////////////////////////////////////////////////////////////
	// methods //
	////////////

	/**
	 * The ordered sequence of members describing the inlined layout, in the same order the field type's own
	 * entity form uses.
	 *
	 * @return the nested members.
	 */
	public XGettingSequence<? extends PersistenceTypeDescriptionMemberField> members();

	@Override
	public default boolean equalsDescription(final PersistenceTypeDescriptionMember other)
	{
		// does NOT call #equalsStructure to avoid redundant member iteration
		return PersistenceTypeDescriptionMember.equalTypeAndNameAndQualifier(this, other)
			&& other instanceof PersistenceTypeDescriptionMemberFieldValueStruct
			&& PersistenceTypeDescriptionMember.equalDescriptions(
				this.members(),
				((PersistenceTypeDescriptionMemberFieldValueStruct)other).members()
			)
		;
	}

	@Override
	public default boolean equalsStructure(final PersistenceTypeDescriptionMember other)
	{
		return PersistenceTypeDescriptionMemberFieldReflective.super.equalsStructure(other)
			&& other instanceof PersistenceTypeDescriptionMemberFieldValueStruct
			&& PersistenceTypeDescriptionMember.equalStructures(
				this.members(),
				((PersistenceTypeDescriptionMemberFieldValueStruct)other).members()
			)
		;
	}

	@Override
	public default boolean equalsLayout(final PersistenceTypeDescriptionMember other)
	{
		return PersistenceTypeDescriptionMemberFieldReflective.super.equalsLayout(other)
			&& other instanceof PersistenceTypeDescriptionMemberFieldValueStruct
			&& PersistenceTypeDescriptionMember.equalLayouts(
				this.members(),
				((PersistenceTypeDescriptionMemberFieldValueStruct)other).members()
			)
		;
	}

	@Override
	public default PersistenceTypeDefinitionMemberFieldValueStruct createDefinitionMember(
		final PersistenceTypeDefinitionMemberCreator creator
	)
	{
		return creator.createDefinitionMember(this);
	}

	/**
	 * Creates an inlined field description.
	 *
	 * @param typeName          the inlined field type's name; must not be {@code null}.
	 * @param declaringTypeName the fully qualified name of the declaring class; must not be {@code null}.
	 * @param name              the field's simple name; must not be {@code null}.
	 * @param members           the members describing the inlined layout; must not be {@code null}.
	 *
	 * @return a new inlined field description.
	 */
	public static PersistenceTypeDescriptionMemberFieldValueStruct New(
		final String typeName         ,
		final String declaringTypeName,
		final String name             ,
		final XGettingSequence<? extends PersistenceTypeDescriptionMemberField> members
	)
	{
		return new PersistenceTypeDescriptionMemberFieldValueStruct.Default(
			notNull(typeName)         ,
			notNull(declaringTypeName),
			notNull(name)             ,
			notNull(members)
		);
	}

	public class Default
	extends PersistenceTypeDescriptionMemberField.Abstract
	implements PersistenceTypeDescriptionMemberFieldValueStruct
	{
		///////////////////////////////////////////////////////////////////////////
		// instance fields //
		////////////////////

		final XImmutableSequence<? extends PersistenceTypeDescriptionMemberField> members;



		///////////////////////////////////////////////////////////////////////////
		// constructors //
		/////////////////

		protected Default(
			final String typeName         ,
			final String declaringTypeName,
			final String name             ,
			final XGettingSequence<? extends PersistenceTypeDescriptionMemberField> members
		)
		{
			super(
				typeName         ,
				declaringTypeName,
				name             ,
				false            , // not a reference: the content is inlined, not pointed to
				false            , // not a primitive: it is a composite of its own members
				PersistenceTypeDescriptionMember.determineHasReferences(members),
				calculateStructLength(members),
				calculateStructLength(members)
			);
			this.members = members.immure();
		}



		///////////////////////////////////////////////////////////////////////////
		// methods //
		////////////

		@Override
		public XGettingSequence<? extends PersistenceTypeDescriptionMemberField> members()
		{
			return this.members;
		}

		@Override
		public void assembleTypeDescription(final PersistenceTypeDescriptionMemberAppender assembler)
		{
			assembler.appendTypeMemberDescription(this);
		}

	}

}
