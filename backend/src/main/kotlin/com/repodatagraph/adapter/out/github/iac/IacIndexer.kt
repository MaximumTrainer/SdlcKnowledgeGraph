package com.repodatagraph.adapter.out.github.iac

import org.springframework.stereotype.Component

/** The infrastructure-as-code formats the ontology declares. */
enum class IacFormat {
    TERRAFORM,
    CDK,
    BICEP,
    CLOUDFORMATION,
    ;

    /** The spelling the ontology declares. */
    fun declared(): String = name.lowercase()
}

/**
 * Indexes infrastructure-as-code files, and the identifiers they name.
 *
 * What is recorded is evidence, not infrastructure. "This repository contains a file claiming a
 * bucket called `acme-payments-receipts` should exist" is true whether or not anyone ever applied
 * it; "this repository owns that bucket" is a conclusion, and drawing it is the link engine's job
 * (#28). Keeping the two apart is what stops a plan nobody ran from becoming an asserted fact.
 *
 * Extraction is deliberately shallow. A Terraform configuration is a program, and evaluating one to
 * see what it would create means running somebody else's code against every repository in an estate.
 * So this reads the identifiers a file states literally and ignores the ones it computes - which
 * means a resource named entirely by interpolation is not indexed, and that is the honest outcome:
 * a name nothing can match is not evidence of anything.
 */
@Component
class IacIndexer {
    /** The format of the file at [path], or null if it is not infrastructure as code. */
    fun formatOf(path: String): IacFormat? {
        val name = path.substringAfterLast('/').lowercase()
        return when {
            name.endsWith(".tf") || name.endsWith(".tfvars") -> IacFormat.TERRAFORM
            name.endsWith(".bicep") -> IacFormat.BICEP
            name == "cdk.json" -> IacFormat.CDK
            // Only by name. Every repository is full of YAML, and indexing all of it would fill the
            // graph with CI workflows and Kubernetes manifests claiming to be CloudFormation.
            CLOUDFORMATION_NAMES.any { name == it || name.endsWith(".$it") } -> IacFormat.CLOUDFORMATION
            else -> null
        }
    }

    /**
     * The identifiers the file names, for something else to match against later.
     *
     * Both halves matter. `aws_s3_bucket.receipts` is how the file refers to the resource, which is
     * what a Terraform state or a plan will say; `acme-payments-receipts` is what the thing is
     * actually called in the cloud, which is what a cloud connector will have seen.
     */
    fun referencesIn(
        format: IacFormat,
        content: String,
    ): List<String> {
        val declared =
            when (format) {
                IacFormat.TERRAFORM -> TERRAFORM_RESOURCE.findAll(content).map { "${it.groupValues[1]}.${it.groupValues[2]}" }
                IacFormat.CLOUDFORMATION -> CLOUDFORMATION_LOGICAL_ID.findAll(content).map { it.groupValues[1] }
                else -> emptySequence()
            }

        return (declared + literals(format, content))
            .filter { it.isIdentifying() }
            .distinct()
            .toList()
    }

    /** Values the file states outright: names, ARNs, and anything else worth matching on. */
    private fun literals(
        format: IacFormat,
        content: String,
    ): Sequence<String> =
        when (format) {
            IacFormat.CLOUDFORMATION -> CLOUDFORMATION_VALUE.findAll(content).map { it.groupValues[1] }
            IacFormat.CDK -> emptySequence()
            else -> QUOTED.findAll(content).map { it.groupValues[1] }
        }

    /**
     * Whether a value could identify one particular thing.
     *
     * `private`, `dev` and `uksouth` are settings, not names, and every repository has them. A graph
     * full of those matches everything, which is the same as matching nothing.
     */
    private fun String.isIdentifying(): Boolean =
        when {
            // A value the file computes is not a name anything can be matched against.
            contains("\${") -> false
            // An ARN identifies exactly one thing, whatever it looks like otherwise.
            startsWith("arn:") -> true
            length < MINIMUM_LENGTH || lowercase() in COMMON_VALUES -> false
            else -> IDENTIFIER.matches(this)
        }

    private companion object {
        val CLOUDFORMATION_NAMES = listOf("template.yaml", "template.yml", "template.json", "cloudformation.yaml", "cloudformation.yml")

        /** `resource "aws_s3_bucket" "receipts" {` */
        val TERRAFORM_RESOURCE = Regex("""resource\s+"([^"]+)"\s+"([^"]+)"""")

        /** A key at the indentation CloudFormation puts logical ids at, under `Resources:`. */
        val CLOUDFORMATION_LOGICAL_ID = Regex("""^\s{2,4}([A-Za-z][A-Za-z0-9]*):\s*$""", RegexOption.MULTILINE)

        /** `BucketName: acme-payments-receipts`, quoted or not. */
        val CLOUDFORMATION_VALUE = Regex("""^\s*\w*Name\w*:\s*["']?([A-Za-z0-9][\w.:/@-]*)["']?\s*$""", RegexOption.MULTILINE)

        val QUOTED = Regex("""['"]([^'"\n]+)['"]""")

        /** Letters, digits and the separators identifiers actually use - not prose, not expressions. */
        val IDENTIFIER = Regex("""[A-Za-z0-9][\w.:/@-]*""")

        /** Long enough to be a name rather than a flag. */
        const val MINIMUM_LENGTH = 4

        val COMMON_VALUES =
            setOf(
                "true",
                "false",
                "null",
                "none",
                "private",
                "public",
                "enabled",
                "disabled",
                "prod",
                "production",
                "dev",
                "development",
                "test",
                "staging",
                "default",
                "string",
                "number",
                "bool",
                "list",
                "map",
                "standard",
                "basic",
                "name",
                "type",
            )
    }
}
