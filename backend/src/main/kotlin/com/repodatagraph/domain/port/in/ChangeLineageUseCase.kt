package com.repodatagraph.domain.port.`in`

import com.repodatagraph.domain.model.DeploymentWorkItems
import com.repodatagraph.domain.model.WorkItemDeployments

/** The two questions change lineage answers (#85). */
interface ChangeLineageUseCase {
    /** Where the work item with this URI is live; refuses a missing uri and a work item not held. */
    fun deploymentsOfWorkItem(uri: String?): WorkItemDeployments

    /** What intent the deployment carries, named by its id or its bare key. */
    fun workItemsOfDeployment(deploymentId: String?): DeploymentWorkItems
}
