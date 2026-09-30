package com.repodatagraph.adapter.`in`.rest

import com.repodatagraph.adapter.`in`.rest.dto.ServicePrincipalListResponse
import com.repodatagraph.adapter.`in`.rest.dto.ServicePrincipalRequest
import com.repodatagraph.adapter.`in`.rest.dto.ServicePrincipalResponse
import com.repodatagraph.domain.exception.ServicePrincipalExistsException
import com.repodatagraph.domain.exception.ServicePrincipalNotFoundException
import com.repodatagraph.domain.exception.ServicePrincipalValidationException
import com.repodatagraph.domain.exception.UnknownOwningTeamException
import com.repodatagraph.domain.exception.UserPrincipalRequiredException
import com.repodatagraph.domain.model.ServicePrincipal
import com.repodatagraph.domain.model.ServicePrincipalRegistration
import com.repodatagraph.domain.port.`in`.ServicePrincipalUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

/**
 * The service principal registry (#115, FR-2 and FR-4): the connectors and agents the graph will act
 * for, each owned by a team. Only a user may register or deregister one; anyone the gate lets in may
 * list them.
 */
@RestController
@RequestMapping(ServicePrincipal.API_PATH)
@Tag(name = "Service principals", description = "Connectors and agents registered as principals of their own")
class ServicePrincipalController(
    private val useCase: ServicePrincipalUseCase,
) {
    @PostMapping
    @Operation(
        operationId = "registerServicePrincipal",
        summary = "Register an identity provider client as a service principal owned by a team (users only)",
    )
    fun register(
        @RequestBody request: ServicePrincipalRequest,
    ): ResponseEntity<ServicePrincipalResponse> {
        val registered =
            useCase.register(
                ServicePrincipalRegistration(
                    name = required("name", request.name),
                    ownedBy = required("ownedBy", request.ownedBy),
                    description = request.description,
                ),
            )
        return ResponseEntity
            .created(URI.create("${ServicePrincipal.API_PATH}/${registered.name}"))
            .body(ServicePrincipalResponse.from(registered))
    }

    @GetMapping
    @Operation(operationId = "listServicePrincipals", summary = "List every registration, deregistered ones with their validTo")
    fun list(): ServicePrincipalListResponse = ServicePrincipalListResponse(useCase.list().map(ServicePrincipalResponse::from))

    @DeleteMapping("/{name}")
    @Operation(
        operationId = "deregisterServicePrincipal",
        summary = "Deregister a service principal: sets its validTo and keeps the record (users only)",
    )
    fun deregister(
        @PathVariable name: String,
    ): ServicePrincipalResponse = ServicePrincipalResponse.from(useCase.deregister(name))

    private fun required(
        field: String,
        value: String?,
    ): String = value?.takeIf { it.isNotBlank() } ?: throw ServicePrincipalValidationException(field, "is required")
}

/** The registry's refusals, in the same shape as the rest of the API. */
@RestControllerAdvice(assignableTypes = [ServicePrincipalController::class])
class ServicePrincipalRestExceptionHandler {
    @ExceptionHandler(ServicePrincipalValidationException::class)
    fun onInvalid(exception: ServicePrincipalValidationException): ResponseEntity<Map<String, Any>> =
        ResponseEntity.badRequest().body(
            mapOf("error" to "invalid service principal", "field" to exception.field, "message" to exception.message),
        )

    @ExceptionHandler(UnknownOwningTeamException::class)
    fun onUnknownTeam(exception: UnknownOwningTeamException): ResponseEntity<Map<String, Any>> =
        ResponseEntity.badRequest().body(mapOf("error" to "unknown team", "ownedBy" to exception.ownedBy))

    @ExceptionHandler(ServicePrincipalExistsException::class)
    fun onExists(exception: ServicePrincipalExistsException): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("error" to "service principal exists", "name" to exception.name))

    @ExceptionHandler(ServicePrincipalNotFoundException::class)
    fun onNotFound(exception: ServicePrincipalNotFoundException): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to "service principal not found", "name" to exception.name))

    @ExceptionHandler(UserPrincipalRequiredException::class)
    fun onNotAUser(exception: UserPrincipalRequiredException): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(HttpStatus.FORBIDDEN).body(mapOf("error" to (exception.message ?: "forbidden")))
}
