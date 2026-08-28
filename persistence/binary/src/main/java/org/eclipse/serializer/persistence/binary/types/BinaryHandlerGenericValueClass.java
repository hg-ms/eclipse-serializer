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

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Parameter;

import org.eclipse.serializer.collections.types.XGettingEnum;
import org.eclipse.serializer.persistence.binary.exceptions.BinaryPersistenceException;
import org.eclipse.serializer.persistence.exceptions.PersistenceExceptionTypeNotPersistable;
import org.eclipse.serializer.persistence.types.PersistenceEagerStoringFieldEvaluator;
import org.eclipse.serializer.persistence.types.PersistenceFieldLengthResolver;
import org.eclipse.serializer.persistence.types.PersistenceLoadHandler;
import org.eclipse.serializer.persistence.types.PersistenceTypeDefinitionMemberFieldReflective;
import org.eclipse.serializer.persistence.types.PersistenceValueInliningResolver;
import org.eclipse.serializer.reflect.XReflect;
import org.eclipse.serializer.util.logging.Logging;
import org.slf4j.Logger;

/**
 * Reflective type handler for value classes (JEP 401).
 * <p>
 * Value instances have no identity and no mutable state: they cannot be allocated blank and filled
 * afterwards like identity instances are. This handler therefore reads all field values from the
 * persisted data first and passes them to a constructor, so the instance is complete the moment it
 * exists. Storing is inherited unchanged, so the persisted form is identical to the one an identity
 * class of the same structure would produce.
 * <p>
 * The constructor is required to accept the persistable instance fields in declaration order, which
 * is exactly what a record's canonical constructor does. If no such constructor exists, the type
 * cannot be handled generically and a custom type handler must be registered for it. This is
 * validated once, at handler creation, rather than at store or load time.
 * <p>
 * Note that {@link #create(Binary, PersistenceLoadHandler)} resolves the instance's references,
 * unlike the identity case where they are resolved in {@code initializeState}. The loader
 * accommodates this by deferring the creation of value instances until their references can be
 * resolved.
 *
 * @param <T> the handled value class.
 */
public final class BinaryHandlerGenericValueClass<T> extends AbstractBinaryHandlerReflective<T>
{
	///////////////////////////////////////////////////////////////////////////
	// constants //
	//////////////

	private final static Logger logger = Logging.getLogger(BinaryHandlerGenericValueClass.class);



	///////////////////////////////////////////////////////////////////////////
	// static methods //
	///////////////////

	/**
	 * Creates a new {@link BinaryHandlerGenericValueClass} for the passed value class.
	 *
	 * @param <T>                  the handled value class.
	 * @param type                 the value class to be handled.
	 * @param typeName             the type name to be used in the type dictionary.
	 * @param persistableFields    the fields to be persisted.
	 * @param persisterFields      the fields to be set to the persister.
	 * @param lengthResolver       the field length resolver.
	 * @param eagerEvaluator       the eager storing evaluator.
	 * @param fieldHandlerProvider the custom field handler provider.
	 * @param switchByteOrder      whether persisted values use a non-native byte order.
	 *
	 * @return the newly created handler.
	 *
	 * @throws PersistenceExceptionTypeNotPersistable if the type has no suitable constructor.
	 */
	public static <T> BinaryHandlerGenericValueClass<T> New(
		final Class<T>                              type                ,
		final String                                typeName            ,
		final XGettingEnum<Field>                   persistableFields   ,
		final XGettingEnum<Field>                   persisterFields     ,
		final PersistenceFieldLengthResolver        lengthResolver      ,
		final PersistenceEagerStoringFieldEvaluator eagerEvaluator      ,
		final BinaryFieldHandlerProvider            fieldHandlerProvider,
		final boolean                               switchByteOrder
	)
	{
		return new BinaryHandlerGenericValueClass<>(
			type                ,
			typeName            ,
			persistableFields   ,
			persisterFields     ,
			lengthResolver      ,
			eagerEvaluator      ,
			fieldHandlerProvider,
			switchByteOrder
		);
	}

	/**
	 * Whether the type has a matching constructor that cannot be made accessible because its module
	 * does not open the package. Such types (e.g. JDK value types) must keep being handled
	 * reflectively, since there is no legal way to invoke their constructor.
	 * <p>
	 * A type without any matching constructor is not reported here: that case is a genuine
	 * persistability problem and is reported by {@link #New} with a fitting explanation.
	 *
	 * @param type              the value class to be tested.
	 * @param persistableFields the fields the constructor would have to accept.
	 *
	 * @return whether the type's constructor is inaccessible.
	 */
	public static boolean isConstructorModuleProtected(
		final Class<?>            type             ,
		final XGettingEnum<Field> persistableFields
	)
	{
		try
		{
			return !type.getDeclaredConstructor(toParameterTypes(persistableFields)).trySetAccessible();
		}
		catch(final NoSuchMethodException e)
		{
			return false;
		}
	}

	/**
	 * Guarantees that the constructor found by parameter types really accepts the fields in their
	 * declaration order.
	 * <p>
	 * Matching by type alone is only unambiguous while all parameter types differ: then there is
	 * exactly one way to assign the fields to them. As soon as two parameters share a type, a
	 * constructor declaring them in the opposite order matches just as well and would silently swap
	 * the two values on every load. Parameter names settle it when the class was compiled with them,
	 * a record settles it by definition, and without either the type cannot be handled generically.
	 */
	private static void validateConstructorOrder(
		final Class<?>            type             ,
		final Constructor<?>      constructor      ,
		final XGettingEnum<Field> persistableFields
	)
	{
		if(type.isRecord() || !hasRepeatedType(constructor.getParameterTypes()))
		{
			return;
		}

		final Parameter[] parameters = constructor.getParameters();

		if(!parameters[0].isNamePresent())
		{
			/* Without parameter names the order cannot be checked at all. Rejecting every such type
			 * would make the common case (a constructor that does list the fields in order) fail, so
			 * this is reported instead of enforced.
			 */
			logger.warn(
				"Value class {} has several constructor parameters of the same type and was compiled"
				+ " without parameter names, so it cannot be verified that its constructor accepts the"
				+ " fields in declaration order. A constructor declaring them in a different order would"
				+ " silently swap their values. Compile with -parameters or use a record to have this"
				+ " checked.",
				type.getName()
			);

			return;
		}

		int i = 0;
		for(final Field field : persistableFields)
		{
			final Parameter parameter = parameters[i++];
			if(!parameter.getName().equals(field.getName()))
			{
				throw new PersistenceExceptionTypeNotPersistable(type,
					new BinaryPersistenceException(
						"Constructor of value class " + type.getName() + " does not accept the fields in"
						+ " declaration order: parameter " + (i - 1) + " is named " + parameter.getName()
						+ " but the field at that position is " + field.getName() + "."
					)
				);
			}
		}
	}

	private static boolean hasRepeatedType(final Class<?>[] parameterTypes)
	{
		for(int i = 0; i < parameterTypes.length; i++)
		{
			for(int j = i + 1; j < parameterTypes.length; j++)
			{
				if(parameterTypes[i] == parameterTypes[j])
				{
					return true;
				}
			}
		}

		return false;
	}

	static MethodHandle resolveConstructor(
		final Class<?>            type             ,
		final Class<?>[]          parameterTypes   ,
		final XGettingEnum<Field> persistableFields
	)
	{
		final Constructor<?> constructor;
		try
		{
			constructor = type.getDeclaredConstructor(parameterTypes);
		}
		catch(final NoSuchMethodException e)
		{
			throw new PersistenceExceptionTypeNotPersistable(type,
				new BinaryPersistenceException(
					"Value class " + type.getName() + " cannot be handled generically: it has no constructor"
					+ " accepting its persistable fields in declaration order"
					+ toParameterListString(parameterTypes)
					+ ". Register a custom type handler for it.",
					e
				)
			);
		}

		validateConstructorOrder(type, constructor, persistableFields);

		try
		{
			/* Pre-adapted to a fixed (Object[])Object shape so creation can use invokeExact instead of
			 * invokeWithArguments, which would redo the argument conversion on every instance.
			 */
			return MethodHandles.lookup().unreflectConstructor(XReflect.setAccessible(constructor))
				.asSpreader(Object[].class, parameterTypes.length)
				.asType(MethodType.methodType(Object.class, Object[].class))
			;
		}
		catch(final IllegalAccessException e)
		{
			throw new PersistenceExceptionTypeNotPersistable(type,
				new BinaryPersistenceException(
					"Constructor of value class " + type.getName() + " is not accessible.", e
				)
			);
		}
	}

	private static String toParameterListString(final Class<?>[] parameterTypes)
	{
		final StringBuilder sb = new StringBuilder(" (");
		for(int i = 0; i < parameterTypes.length; i++)
		{
			if(i != 0)
			{
				sb.append(", ");
			}
			sb.append(parameterTypes[i].getName());
		}

		return sb.append(')').toString();
	}



	///////////////////////////////////////////////////////////////////////////
	// instance fields //
	////////////////////

	private final MethodHandle       constructor    ;

	// all three arrays are parallel and indexed in persisted (= storing) member order.
	private final BinaryValueReader[] readers       ;
	private final long[]              readerOffsets ;
	private final int[]               argumentIndices;



	///////////////////////////////////////////////////////////////////////////
	// constructors //
	/////////////////

	BinaryHandlerGenericValueClass(
		final Class<T>                              type                ,
		final String                                typeName            ,
		final XGettingEnum<Field>                   persistableFields   ,
		final XGettingEnum<Field>                   persisterFields     ,
		final PersistenceFieldLengthResolver        lengthResolver      ,
		final PersistenceEagerStoringFieldEvaluator eagerEvaluator      ,
		final BinaryFieldHandlerProvider            fieldHandlerProvider,
		final boolean                               switchByteOrder
	)
	{
		super(
			type                ,
			typeName            ,
			persistableFields   ,
			persisterFields     ,
			lengthResolver      ,
			eagerEvaluator      ,
			fieldHandlerProvider,

			/* This handler reads its members through readers rather than setters, since an identity-less
			 * instance is constructed rather than populated. Inlining a field would need a reader for the
			 * inlined layout, which does not exist yet, so the fields of a value class stay referenced.
			 */
			PersistenceValueInliningResolver.Disabled(),
			switchByteOrder
		);

		final XGettingEnum<? extends PersistenceTypeDefinitionMemberFieldReflective> storingMembers =
			this.storingMembers()
		;
		final int memberCount = storingMembers.intSize();

		this.readers         = new BinaryValueReader[memberCount];
		this.readerOffsets   = new long[memberCount]             ;
		this.argumentIndices = new int[memberCount]              ;

		// members are persisted in storing order (references first), but constructed in declaration order.
		long offset = 0;
		int  i      = 0;
		for(final PersistenceTypeDefinitionMemberFieldReflective member : storingMembers)
		{
			this.readers[i]         = BinaryValueReader.provideReader(member.type());
			this.readerOffsets[i]   = offset;
			this.argumentIndices[i] = indexOfField(persistableFields, member.field());
			offset += member.persistentMinimumLength();
			i++;
		}

		validateNoCustomFieldHandlers(type, persistableFields, fieldHandlerProvider, switchByteOrder);

		this.constructor = resolveConstructor(type, toParameterTypes(persistableFields), persistableFields);
	}

	/**
	 * Rejects a value class with a custom field handler registered for one of its fields.
	 * <p>
	 * A custom field handler is a pair: a storer writing the field's own representation and a setter
	 * writing the value back <em>into an instance</em>. The second half is not applicable here, since
	 * a value instance is constructed from its field values rather than populated, so honoring the
	 * custom representation on the storing side while reading it back generically would silently
	 * produce wrong values.
	 */
	private static void validateNoCustomFieldHandlers(
		final Class<?>                   type                ,
		final XGettingEnum<Field>        persistableFields   ,
		final BinaryFieldHandlerProvider fieldHandlerProvider,
		final boolean                    switchByteOrder
	)
	{
		for(final Field field : persistableFields)
		{
			if(fieldHandlerProvider.lookupFieldStorer(field, false, switchByteOrder) == null
				&& fieldHandlerProvider.lookupFieldStorer(field, true, switchByteOrder) == null
				&& fieldHandlerProvider.lookupFieldSetter(field, switchByteOrder) == null
			)
			{
				continue;
			}

			throw new PersistenceExceptionTypeNotPersistable(type,
				new BinaryPersistenceException(
					"Field " + field.getName() + " of value class " + type.getName() + " has a custom field"
					+ " handler registered, which cannot be applied to a type whose instances are created"
					+ " by their constructor. Register a custom type handler for " + type.getName()
					+ " instead."
				)
			);
		}
	}

	static Class<?>[] toParameterTypes(final XGettingEnum<Field> persistableFields)
	{
		final Class<?>[] parameterTypes = new Class<?>[persistableFields.intSize()];

		int i = 0;
		for(final Field field : persistableFields)
		{
			parameterTypes[i++] = field.getType();
		}

		return parameterTypes;
	}

	private static int indexOfField(final XGettingEnum<Field> persistableFields, final Field field)
	{
		int i = 0;
		for(final Field persistableField : persistableFields)
		{
			if(persistableField.equals(field))
			{
				return i;
			}
			i++;
		}

		// cannot happen: members are derived from exactly these fields.
		throw new BinaryPersistenceException("Unknown field " + field + " for value class handler.");
	}



	///////////////////////////////////////////////////////////////////////////
	// methods //
	////////////

	@Override
	public T create(final Binary data, final PersistenceLoadHandler handler)
	{
		final Object[] arguments = new Object[this.readers.length];
		for(int i = 0; i < this.readers.length; i++)
		{
			arguments[this.argumentIndices[i]] = this.readers[i].readValue(data, this.readerOffsets[i], handler);
		}

		try
		{
			// cast safety guaranteed by the constructor being the handled type's own.
			@SuppressWarnings("unchecked")
			final T instance = (T)this.constructor.invokeExact(arguments);

			return instance;
		}
		catch(final Throwable t)
		{
			// a failure of the JVM itself is none of this handler's business.
			if(t instanceof Error)
			{
				throw (Error)t;
			}

			/* A value class constructor validating its arguments can legitimately reject persisted
			 * state, e.g. when a field was added and the missing value is defaulted by a mapping.
			 */
			throw new BinaryPersistenceException(
				"Failed to construct instance of value class " + this.type().getName()
				+ " for objectId " + data.getBuildItemObjectId() + ".",
				t
			);
		}
	}

	@Override
	public void initializeState(final Binary data, final T instance, final PersistenceLoadHandler handler)
	{
		// value instances are complete when created, there is no state to initialize afterwards.
	}

	@Override
	public void updateState(final Binary data, final T instance, final PersistenceLoadHandler handler)
	{
		/* Updating means replacing the state of an existing instance, which is impossible for an
		 * immutable one. Since a value instance is always created from the very data that would be
		 * applied here, there is also nothing to do.
		 */
	}

}
