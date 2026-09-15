package com.repodatagraph.adapter.out.github.iac

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * An IaC file is evidence, not a resource.
 *
 * The graph records that a repository contains a file claiming a bucket called
 * `acme-payments-receipts` should exist. Whether such a bucket does exist, and whether it is the one
 * the cloud connector found, is the link engine's question (#28) - and keeping the two apart is what
 * stops a plan somebody never applied from becoming an asserted fact about infrastructure.
 *
 * Extraction is therefore deliberately shallow: identifiers a file names literally, found without
 * evaluating anything. A Terraform file is a program, and running one to see what it would create is
 * not something an ingestion job should do to every repository in an estate.
 */
class IacIndexerTest {
    private val indexer = IacIndexer()

    @Nested
    inner class WhatItRecognises {
        @Test
        fun `knows terraform by extension`() {
            assertThat(indexer.formatOf("infra/main.tf")).isEqualTo(IacFormat.TERRAFORM)
            assertThat(indexer.formatOf("infra/variables.tfvars")).isEqualTo(IacFormat.TERRAFORM)
        }

        @Test
        fun `knows bicep and cdk by name`() {
            assertThat(indexer.formatOf("infra/main.bicep")).isEqualTo(IacFormat.BICEP)
            assertThat(indexer.formatOf("cdk.json")).isEqualTo(IacFormat.CDK)
        }

        @Test
        fun `only treats YAML as CloudFormation when its name says so`() {
            assertThat(indexer.formatOf("template.yaml")).isEqualTo(IacFormat.CLOUDFORMATION)
            assertThat(indexer.formatOf("infra/payments.template.yml")).isEqualTo(IacFormat.CLOUDFORMATION)
            // Every repository is full of YAML. Indexing all of it as infrastructure would fill the
            // graph with CI workflows and Kubernetes manifests claiming to be CloudFormation.
            assertThat(indexer.formatOf(".github/workflows/ci.yml")).isNull()
            assertThat(indexer.formatOf("docker-compose.yaml")).isNull()
        }

        @Test
        fun `ignores a file that is not infrastructure at all`() {
            assertThat(indexer.formatOf("src/main/kotlin/Main.kt")).isNull()
            assertThat(indexer.formatOf("README.md")).isNull()
        }
    }

    @Nested
    inner class Terraform {
        @Test
        fun `names the resources it declares, type and name together`() {
            val refs =
                indexer.referencesIn(
                    IacFormat.TERRAFORM,
                    """
                    resource "aws_s3_bucket" "receipts" {
                      bucket = "acme-payments-receipts"
                    }

                    resource "aws_sqs_queue" "events" {
                      name = "acme-payments-events"
                    }
                    """.trimIndent(),
                )

            assertThat(refs).contains("aws_s3_bucket.receipts", "aws_sqs_queue.events")
            // The literal names too: these are what a cloud connector will have seen, and matching on
            // them is the whole point of recording the file.
            assertThat(refs).contains("acme-payments-receipts", "acme-payments-events")
        }

        @Test
        fun `keeps an ARN wherever it appears`() {
            val refs =
                indexer.referencesIn(
                    IacFormat.TERRAFORM,
                    """role_arn = "arn:aws:iam::123456789012:role/payments-task"""",
                )

            assertThat(refs).contains("arn:aws:iam::123456789012:role/payments-task")
        }

        @Test
        fun `does not record an interpolation as an identifier`() {
            val refs = indexer.referencesIn(IacFormat.TERRAFORM, """bucket = "${'$'}{var.prefix}-receipts"""")

            // A value the file computes is not a name anything can be matched against; recording it
            // would put a reference in the graph that matches nothing, for ever.
            assertThat(refs).doesNotContain("\${var.prefix}-receipts")
        }

        @Test
        fun `ignores a value too short or too generic to identify anything`() {
            val refs =
                indexer.referencesIn(
                    IacFormat.TERRAFORM,
                    """
                    resource "aws_s3_bucket" "b" {
                      acl  = "private"
                      tags = { Env = "dev" }
                    }
                    """.trimIndent(),
                )

            assertThat(refs).doesNotContain("private", "dev", "Env")
        }
    }

    @Nested
    inner class Bicep {
        @Test
        fun `names what each resource is called`() {
            val refs =
                indexer.referencesIn(
                    IacFormat.BICEP,
                    """
                    resource storage 'Microsoft.Storage/storageAccounts@2023-01-01' = {
                      name: 'acmepaymentsstore'
                      location: 'uksouth'
                    }
                    """.trimIndent(),
                )

            assertThat(refs).contains("acmepaymentsstore")
        }
    }

    @Nested
    inner class CloudFormation {
        @Test
        fun `names logical ids and the physical names they ask for`() {
            val refs =
                indexer.referencesIn(
                    IacFormat.CLOUDFORMATION,
                    """
                    Resources:
                      ReceiptsBucket:
                        Type: AWS::S3::Bucket
                        Properties:
                          BucketName: acme-payments-receipts
                    """.trimIndent(),
                )

            assertThat(refs).contains("ReceiptsBucket", "acme-payments-receipts")
        }
    }
}
