package com.statsig.sdk

internal class EvaluationContext(
    var user: StatsigUser,
    var evaluation: ConfigEvaluation = ConfigEvaluation(),
    var clientSDKKey: String? = null,
    var hash: HashAlgo = HashAlgo.SHA256,
    var isNested: Boolean = false,
) {
    // Overload without default parameters required for Java
    constructor(user: StatsigUser) : this(user, ConfigEvaluation())

    constructor(ctx: EvaluationContext) : this(
        user = ctx.user,
        evaluation = ctx.evaluation,
        clientSDKKey = ctx.clientSDKKey,
        hash = ctx.hash,
        isNested = ctx.isNested,
    )

    internal fun asDelegate(): EvaluationContext {
        var context = EvaluationContext(this)
        context.evaluation.isDelegate = true
        return context
    }

    internal fun asNested(): EvaluationContext {
        var context = EvaluationContext(this)
        context.isNested = isNested
        return context
    }

    internal fun asNewEvaluation(): EvaluationContext {
        var context = EvaluationContext(this)
        context.evaluation = ConfigEvaluation()
        return context
    }
}
