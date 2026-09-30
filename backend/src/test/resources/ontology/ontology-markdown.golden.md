# SDLC knowledge graph ontology v1.3.0

Node types with their properties, then the relationships between them. `!` marks a required property, `a|b` lists the only values allowed, `[format]` names a value's shape and `e.g.` shows one. Meta types and deprecated properties are left out: GET /api/v1/ontology has everything.

## Repository
A git repository, the anchor for most of the graph. Key: name.
Answers: Which team owns this repository?
- name: string! e.g. payments
- url: string! [url] e.g. https://github.com/acme/payments
- topics: string[] e.g. ["billing"]
Out: OWNS_RESOURCE CloudResource

## CloudResource
An infrastructure object in AWS, Azure or GCP. Key: provider, resourceId.
- provider: string! aws|azure|gcp e.g. aws
- resourceId: string! [arn when provider=aws] e.g. arn:aws:s3:::acme-logs
In: OWNS_RESOURCE Repository

## Relationships
- OWNS_RESOURCE (Repository -> CloudResource, read back as OWNED_BY_REPO): A repository is responsible for a piece of infrastructure.
  - rule: string manual|tag|iac e.g. tag
