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

import java.lang.reflect.Field;
import java.util.function.BiPredicate;

import org.eclipse.serializer.collections.HashEnum;
import org.eclipse.serializer.collections.types.XGettingEnum;
import org.eclipse.serializer.reflect.XReflect;

/**
 * Decides whether an owner's field is written into the owner's own binary form instead of being referenced
 * by an object id, and supplies the inlined type's fields when it is.
 * <p>
 * Inlining removes one entity per owner, which is what an identity-less field type would otherwise cost on
 * every store of the owner: it has no object registry entry, so it is assigned a new object id and stored
 * again each time, leaving a superseded copy behind. In exchange the owner's persistent layout embeds the
 * inlined type's layout, which makes evolving that type an evolution of every owner. That trade is the
 * caller's to make, so inlining is opt-in and off by default.
 *
 * @see PersistenceTypeDescriptionMemberFieldValueStruct
 */
@FunctionalInterface
public interface PersistenceValueInliningResolver
{
	/**
	 * Decides whether the passed field is inlined into its owner.
	 *
	 * @param ownerType the class declaring the field.
	 * @param field     the field to decide about.
	 *
	 * @return the inlined type's persistable fields, or {@code null} if the field is not inlined.
	 */
	public XGettingEnum<Field> resolveInlinedFields(Class<?> ownerType, Field field);

	/**
	 * @return a resolver that inlines nothing, which is the default.
	 */
	public static PersistenceValueInliningResolver Disabled()
	{
		return (ownerType, field) -> null;
	}

	/**
	 * Creates a resolver that inlines every field the passed selector accepts and that is eligible: the field's
	 * declared type must be a value class whose own persistable fields are all primitives.
	 * <p>
	 * The type must be a value class because only an identity-less instance can be reconstructed from its
	 * content alone without changing what the owner refers to. It must be the field's declared type because
	 * the inlined form carries no type information, so a field typed as a supertype could not be read back.
	 * Restricting the inlined fields to primitives keeps the inlined slot free of object ids, which is what
	 * lets the storage engine skip it as it skips any other fixed-length non-reference member.
	 *
	 * @param typeAnalyzer the analyzer determining a type's persistable fields; must not be {@code null}.
	 * @param selector     decides which eligible fields to actually inline; must not be {@code null}.
	 *
	 * @return the new resolver.
	 */
	public static PersistenceValueInliningResolver New(
		final PersistenceTypeAnalyzer            typeAnalyzer,
		final BiPredicate<Class<?>, Field>       selector
	)
	{
		return new PersistenceValueInliningResolver.Default(
			notNull(typeAnalyzer),
			notNull(selector)
		);
	}

	public final class Default implements PersistenceValueInliningResolver
	{
		///////////////////////////////////////////////////////////////////////////
		// instance fields //
		////////////////////

		private final PersistenceTypeAnalyzer      typeAnalyzer;
		private final BiPredicate<Class<?>, Field> selector    ;



		///////////////////////////////////////////////////////////////////////////
		// constructors //
		/////////////////

		Default(
			final PersistenceTypeAnalyzer      typeAnalyzer,
			final BiPredicate<Class<?>, Field> selector
		)
		{
			super();
			this.typeAnalyzer = typeAnalyzer;
			this.selector     = selector    ;
		}



		///////////////////////////////////////////////////////////////////////////
		// override methods //
		/////////////////////

		@Override
		public XGettingEnum<Field> resolveInlinedFields(final Class<?> ownerType, final Field field)
		{
			final Class<?> valueType = field.getType();

			if(!XReflect.isValueClass(valueType) || !this.selector.test(ownerType, field))
			{
				return null;
			}

			final HashEnum<Field> persistable = HashEnum.New();
			final HashEnum<Field> persister   = HashEnum.New();
			final HashEnum<Field> problematic = HashEnum.New();
			this.typeAnalyzer.collectPersistableFieldsEntity(valueType, persistable, persister, problematic);

			if(!persister.isEmpty() || !problematic.isEmpty())
			{
				return null;
			}

			for(final Field valueField : persistable)
			{
				if(!valueField.getType().isPrimitive())
				{
					// an inlined reference would need reachability handling the storage engine cannot see
					return null;
				}
			}

			return persistable;
		}

	}

}
