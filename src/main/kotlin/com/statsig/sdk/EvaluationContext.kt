package com.statsig.sdk

import com.statsig.sdk.persistent_storage.UserPersistedValues

internal class EvaluationContext(
    var user: StatsigUser,
    var evaluation: ConfigEvaluation = ConfigEvaluation(),
    var clientSDKKey: String? = null,
    var hash: HashAlgo = HashAlgo.SHA256,
    var isNested: Boolean = false,
    var userPersistedValues: UserPersistedValues? = null,
    var persistentAssignmentOptions: PersistentAssignmentOptions? = null,
    var onlyEvaluateTargeting: Boolean = false,
    var onlyEvaluateOverrides: Boolean = false,
) {
    // Overload without default parameters required for Java
    constructor(user: StatsigUser) : this(user, ConfigEvaluation())

    constructor(ctx: EvaluationContext) : this(
        user = ctx.user,
        evaluation = ctx.evaluation,
        clientSDKKey = ctx.clientSDKKey,
        hash = ctx.hash,
        isNested = ctx.isNested,
        userPersistedValues = ctx.userPersistedValues,
        persistentAssignmentOptions = ctx.persistentAssignmentOptions,
        onlyEvaluateTargeting = ctx.onlyEvaluateTargeting,
        onlyEvaluateOverrides = ctx.onlyEvaluateOverrides,
    )

    internal fun asDelegate(): EvaluationContext {
        var context = EvaluationContext(this)
        context.evaluation.isDelegate = true
        return context
    }

    internal fun asNested(): EvaluationContext {
        var context = EvaluationContext(this)
        context.isNested = true
        return context
    }

    internal fun asNewEvaluation(): EvaluationContext {
        var context = EvaluationContext(this)
        context.evaluation = ConfigEvaluation()
        return context
    }

    internal fun onlyForTargeting(): EvaluationContext {
        var context = EvaluationContext(this)
        context.onlyEvaluateTargeting = true
        return context
    }

    internal fun onlyForOverrides(): EvaluationContext {
        var context = EvaluationContext(this)
        context.onlyEvaluateOverrides = true
        return context
    }
}
