# xApocalypse 1.7.2 Modrinth Update Checker Guide

This build adds a notification-only Modrinth update checker. It checks for newer listed stable releases on startup and every six hours, then notifies the console and players with `xapocalypse.admin`.

## Setup

1. Use Paper 26.2 or newer and Java 25.
2. Put `xApocalypse-1.7.2.jar` in the server's `plugins` folder and remove the older xApocalypse JAR.
3. Fully restart and confirm xApocalypse 1.7.2 enables without errors.
4. Confirm `update-checker.enabled` and `update-checker.notify-admins` were added to `plugins/xApocalypse/config.yml`.
5. Use `/xa reload` after changing either setting.

## Safety boundary

The checker only reads release metadata from Modrinth. It never downloads a JAR, replaces plugin files, or restarts the server.

## Pass condition

The server reaches `Done`, `/xa reload` completes cleanly, and no update warning appears while 1.7.2 is newer than the latest listed Modrinth release.
