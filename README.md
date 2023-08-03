# Statsig Java/Kotlin Server SDK

[![Test](https://github.com/statsig-io/java-server-sdk/actions/workflows/build-and-test.yml/badge.svg)](https://github.com/statsig-io/java-server-sdk/actions/workflows/build-and-test.yml)

The Statsig Java/Kotlin SDK for multi-user, server side environments. If you need a SDK for another language or single user client environment, check out our [other SDKs](https://docs.statsig.com/#sdks).

## Installation

SDK versions below 1.0.0 are available via jitpack. Starting with v1.0.0, we started publishing the SDK only to MavenCentral.

## What is Statsig

Statsig helps you move faster with Feature Gates (Feature Flags) and Dynamic Configs. It also allows you to run A/B tests to validate your new features and understand their impact on your KPIs. If you're new to Statsig, create an account at [statsig.com](https://www.statsig.com).

## Getting Started

Check out our [SDK docs](https://docs.statsig.com/server/javaSdk) to get started.

## Testing

Each server SDK is tested at multiple levels - from unit to integration and e2e tests. Our internal e2e test harness runs daily against each server SDK, while unit and integration tests can be seen in the respective github repos of each SDK. The `ServerSDKConsistencyTest` runs a validation test on local rule/condition evaluation for this SDK against the results in the statsig backend.
