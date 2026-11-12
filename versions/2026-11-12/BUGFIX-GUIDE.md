# xApocalypse 1.7.1 bStats Rebuild Guide

This rebuild adds anonymous bStats metrics using xApocalypse's registered plugin ID `35281`. Gameplay behavior is unchanged from the live-tested 1.7.1 release.

## Setup

1. Use Paper 26.2 and Java 25.
2. Put `xApocalypse-1.7.1.jar` in the server's `plugins` folder and remove the older xApocalypse JAR.
3. Fully restart, then confirm xApocalypse 1.7.1 enables without errors.
4. Confirm `plugins/bStats/config.yml` exists. Set its global `enabled` option to `false` if metrics are not wanted.

## Pass condition

xApocalypse reaches its normal enabled state, the server reaches `Done`, and shutdown completes without bStats-related errors.
