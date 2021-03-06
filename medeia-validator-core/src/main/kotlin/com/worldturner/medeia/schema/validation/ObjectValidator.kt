package com.worldturner.medeia.schema.validation

import com.worldturner.medeia.api.FailedValidationResult
import com.worldturner.medeia.api.OkValidationResult
import com.worldturner.medeia.api.ValidationResult
import com.worldturner.medeia.parser.JsonTokenData
import com.worldturner.medeia.parser.JsonTokenLocation
import com.worldturner.medeia.parser.JsonTokenType.END_OBJECT
import com.worldturner.medeia.parser.JsonTokenType.FIELD_NAME
import com.worldturner.medeia.parser.JsonTokenType.START_OBJECT
import com.worldturner.medeia.schema.validation.stream.SchemaValidatorInstance
import com.worldturner.util.mapToMapTo
import com.worldturner.util.unmodifiableEmptyMutableMap
import java.net.URI
import java.util.IdentityHashMap

class ObjectValidator(
    val maxProperties: Int? = null,
    val minProperties: Int? = null,
    val required: Set<String>? = null,
    val additionalProperties: SchemaValidator? = null,
    val properties: Map<String, SchemaValidator>? = null,
    val patternProperties: Map<Regex, SchemaValidator>? = null,
    val propertyNames: SchemaValidator? = null
) : SchemaValidator {
    override fun createInstance(startLevel: Int): SchemaValidatorInstance =
        ObjectValidatorInstance(this, startLevel)

    override fun recordUnknownRefs(unknownRefs: MutableCollection<URI>) {
        additionalProperties?.let { it.recordUnknownRefs(unknownRefs) }
        properties?.values?.forEach { it.recordUnknownRefs(unknownRefs) }
        patternProperties?.values?.forEach { it.recordUnknownRefs(unknownRefs) }
        propertyNames?.let { it.recordUnknownRefs(unknownRefs) }
    }

    companion object {
        fun create(
            maxProperties: Int?,
            minProperties: Int?,
            required: Set<String>?,
            additionalProperties: SchemaValidator?,
            properties: Map<String, SchemaValidator>?,
            patternProperties: Map<Regex, SchemaValidator>?,
            propertyNames: SchemaValidator?,
            dependencies: Map<String, SchemaValidator>?
        ): SchemaValidator? =
            when {
                dependencies != null ->
                    ObjectDependenciesValidator(
                        ObjectValidator(
                            maxProperties, minProperties, required, additionalProperties,
                            properties, patternProperties, propertyNames
                        ),
                        dependencies
                    )
                isAnyNotNull(
                    maxProperties, minProperties, required, additionalProperties,
                    properties, patternProperties, propertyNames
                ) ->
                    ObjectValidator(
                        maxProperties, minProperties, required, additionalProperties,
                        properties, patternProperties, propertyNames
                    )
                else -> null
            }
    }
}

class ObjectDependenciesValidator(
    val validator: ObjectValidator,
    val dependencies: Map<String, SchemaValidator>
) : SchemaValidator {
    override fun createInstance(startLevel: Int): SchemaValidatorInstance =
        ObjectDependenciesValidatorInstance(validator, dependencies, startLevel)

    override fun recordUnknownRefs(unknownRefs: MutableCollection<URI>) {
        validator.recordUnknownRefs(unknownRefs)
        dependencies.values.forEach { it.recordUnknownRefs(unknownRefs) }
    }
}

open class ObjectValidatorInstance(
    val validator: ObjectValidator,
    val startLevel: Int
) : SchemaValidatorInstance {

    private var first = true
    private var currentPropertyValidator: SchemaValidatorInstance? = null
    private var currentPropertyResult: ValidationResult? = null
    private var currentAdditionalPropertiesValidator: SchemaValidatorInstance? = null
    private var currentAdditionalPropertiesResult: ValidationResult? = null
    private var currentPatternPropertyValidators: List<SchemaValidatorInstance>? = null
    private var currentPatternPropertyResults: MutableMap<SchemaValidatorInstance, ValidationResult> =
        validator.patternProperties?.let { IdentityHashMap<SchemaValidatorInstance, ValidationResult>() }
            ?: unmodifiableEmptyMutableMap()
    private var currentPropertyName: String? = null

    open fun processDependencies(token: JsonTokenData, location: JsonTokenLocation) {
    }

    override fun validate(token: JsonTokenData, location: JsonTokenLocation): ValidationResult? {
        if (first && token.type != START_OBJECT) {
            return OkValidationResult
        }
        first = false
        processDependencies(token, location)
        if (location.level == startLevel + 1) {
            if (token.type == FIELD_NAME) {
                val property = token.text!!
                if (property in location.propertyNames) {
                    return FailedValidationResult(
                        rule = "duplicate",
                        location = location,
                        property = property,
                        message = "Duplicate property name in json object"
                    )
                }
                if (validator.properties != null && property in validator.properties) {
                    currentPropertyValidator = validator.properties[property]!!.createInstance(location.level)
                }
                currentPatternPropertyValidators =
                    validator.patternProperties?.filterKeys { k -> k.containsMatchIn(property) }?.values
                        ?.map { it.createInstance(location.level) }
                if (currentPropertyValidator == null && currentPatternPropertyValidators?.isEmpty() != false &&
                    validator.additionalProperties != null
                ) {
                    currentAdditionalPropertiesValidator =
                        validator.additionalProperties.createInstance(location.level)
                }
                currentPropertyName = property
            }
        }
        if ((location.level == startLevel + 1 && token.type != FIELD_NAME) ||
            location.level > startLevel + 1
        ) {
            currentPropertyValidator?.let {
                if (currentPropertyResult == null) {
                    currentPropertyResult = it.validate(token, location)
                }
            }
            currentPatternPropertyValidators?.forEach {
                if (it !in currentPatternPropertyResults) {
                    val result = it.validate(token, location)
                    if (result != null) {
                        currentPatternPropertyResults[it] = result
                    }
                }
            }
            currentAdditionalPropertiesValidator?.let {
                if (currentAdditionalPropertiesResult == null) {
                    currentAdditionalPropertiesResult = it.validate(token, location)
                }
            }
        }
        if (location.level == startLevel + 1 && token.type.lastToken) {
            currentPropertyResult?.let {
                if (it is FailedValidationResult) {
                    return FailedValidationResult(
                        location = location,
                        rule = "properties",
                        property = currentPropertyName!!,
                        message = "Property validation failed",
                        details = setOf(it)
                    )
                }
            }
            val invalidPatterns =
                currentPatternPropertyResults.values.filterIsInstance(FailedValidationResult::class.java)
            if (invalidPatterns.isNotEmpty()) {
                return FailedValidationResult(
                    location = location,
                    rule = "patternProperties",
                    property = currentPropertyName!!,
                    message = "Pattern property validation failed",
                    details = invalidPatterns.toSet()
                )
            }
            currentAdditionalPropertiesResult?.let {
                if (it is FailedValidationResult) {
                    return FailedValidationResult(
                        location = location,
                        rule = "additionalProperties",
                        property = currentPropertyName!!,
                        message = "Additional properties validation failed",
                        details = setOf(it)
                    )
                }
            }
            currentPropertyValidator = null
            currentPropertyResult = null
            currentPatternPropertyValidators = null
            currentPatternPropertyResults.clear()
            currentAdditionalPropertiesValidator = null
            currentAdditionalPropertiesResult = null
            currentPropertyName = null
        }
        if (location.level == startLevel && token.type == END_OBJECT) {
            return finalStep(location)
        }
        return null
    }

    private fun finalStep(location: JsonTokenLocation): ValidationResult {
        val propertyNamesSeen = location.propertyNames
        validator.maxProperties?.let {
            if (propertyNamesSeen.size > it) {
                return FailedValidationResult(
                    location = location,
                    rule = "maxProperties",
                    message = "Value ${propertyNamesSeen.size} is greater than maxProperties $it"
                )
            }
        }
        validator.minProperties?.let {
            if (propertyNamesSeen.size < it) {
                return FailedValidationResult(
                    location = location,
                    rule = "minProperties",
                    message = "Value ${propertyNamesSeen.size} is smaller than minProperties $it"
                )
            }
        }
        validator.propertyNames?.let { propertyNames ->
            val instance = propertyNames.createInstance(location.level)
            propertyNamesSeen.forEach {
                val textTokenData = JsonTokenData.createText(it)
                val result = instance.validate(textTokenData, location)
                if (result == null)
                    throw NullPointerException("Invalid state")
                else if (result is FailedValidationResult) {
                    return FailedValidationResult(
                        rule = "propertyNames",
                        location = location,
                        property = it,
                        message = "Property name validation failed",
                        details = setOf(result)
                    )
                }
            }
        }
        validator.required?.let { required ->
            required.forEach { property ->
                if (property !in propertyNamesSeen)
                    return FailedValidationResult(
                        location = location,
                        rule = "required",
                        property = property,
                        message = "Required property $property is missing from object"
                    )
            }
        }

        return dependenciesFinalStep(location)
    }

    open fun dependenciesFinalStep(location: JsonTokenLocation): ValidationResult =
        OkValidationResult
}

class ObjectDependenciesValidatorInstance(
    validator: ObjectValidator,
    dependencies: Map<String, SchemaValidator>,
    startLevel: Int
) : ObjectValidatorInstance(validator, startLevel) {

    private var first = true
    private val dependencies =
        dependencies.mapToMapTo(mutableMapOf()) { (k, v) ->
            k to v.createInstance(startLevel)
        }
    private val dependenciesResults = mutableMapOf<String, ValidationResult>()

    override fun processDependencies(token: JsonTokenData, location: JsonTokenLocation) {
        dependencies.forEach { (property, instance) ->
            if (property !in dependenciesResults) {
                val result = instance.validate(token, location)
                result?.let {
                    dependenciesResults.put(property, it)
                }
            }
        }
    }

    override fun dependenciesFinalStep(location: JsonTokenLocation): ValidationResult {
        val propertyNamesSeen = location.propertyNames
        dependenciesResults.forEach { (property, result) ->
            if (property in propertyNamesSeen && result is FailedValidationResult) {
                return FailedValidationResult(
                    location = location,
                    rule = "dependencies",
                    property = property,
                    message = "Dependency for $property failed",
                    details = setOf(result)
                )
            }
        }
        return OkValidationResult
    }
}
