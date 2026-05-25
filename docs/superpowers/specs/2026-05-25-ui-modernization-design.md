# UI Modernization — Launcher & Client (Design Spec)

**Date:** 2026-05-25
**Status:** Approved (design), pending implementation plan
**Repos in scope:**
- `mage-modern` (this repo) — the XMage **client** (`Mage.Client`)
- `DarrellBest/Launcher` (fork of `magefree/Launcher`, cloned to `/home/dbest/projects/Launcher`) — the **launcher**

## Goal

Make the launcher and client **look and feel like a modern game** instead of the current "blocky, 90s" Swing aesthetic. **Modernize visual presentation only — change no functionality.**

## Hard Constraints

- **No behavior changes.** Every button, menu, dialog, table, deck-editor action, and game interaction must work exactly as before. This is a re-skin, not a refactor.
- **No layout restructuring** of the 65 NetBeans-generated `initComponents` screens. We change styling, not component graphs.
- **The game board is out of scope.** Cards and the battlefield are image-based custom-painted art; they remain unchanged. We modernize the *chrome* around the board (frames, panels, toolbars, dialogs, lobby, deck editor, login).
- **Java 8 compatibility** must be preserved (`java.version` = 1.8 in `pom.xml`). FlatLaf supports Java 8+.

## Approach

Replace the look-and-feel engine in both apps with **[FlatLaf](https://www.formdev.com/flatlaf/)** (Apache-2.0 licensed, Java 8+, the L&F behind IntelliJ IDEA).

Why FlatLaf:
- It **re-skins existing Swing components in place** — no functional or layout code changes required.
- Modern flat surfaces, rounded corners, soft borders, modern scrollbars/tabs, real dark mode.
- Fully driven by `UIManager` properties, so we can express the whole theme as a set of keys.
- Ships a clean UI font via a separate artifact (`com.formdev:flatlaf-fonts-inter`) — easy to add, no manual font-bundling.

Current state being replaced:
- **Client:** Nimbus L&F, configured in `GuiDisplayUtil.refreshThemeSettings()`, customized via a `ThemeType` enum that overrides Nimbus keys (`nimbusBase`, `nimbusBlueGrey`, `control`, `nimbusLightBackground`, `info`, `text`) and swaps background images. Six themes exist: Default, Grey, Vaporwave Sunset, Coffee, Island, Carbon Fiber.
- **Launcher:** Java's cross-platform **Metal** L&F (`XMageLauncher.java:492` — `UIManager.getCrossPlatformLookAndFeelClassName()`). Plain Swing with `GridBagLayout`, a background JPEG, the XMage logo, a black console `JTextArea`, and a button column (Launch Client / Client+Server / Server / Check / Update).

## The "Arcane" Dark Theme

A shared visual identity across both apps (deep arcane/fantasy direction).

| Token | Value | Use |
|-------|-------|-----|
| Surface base | `#1A1626` | App background, deepest layer |
| Surface raised | `#221C33` | Panels, cards, dialogs |
| Surface overlay | `#2C2440` | Inputs, hover, table rows |
| Accent primary | `#3DD6C4` (arcane-teal) | Focus rings, selection, primary actions |
| Accent alt | `#E0B341` (gold) | Primary call-to-action buttons (e.g. Launch) |
| Text primary | `#ECE8F5` | Body text |
| Text secondary | `#9C93B8` | Muted/secondary text |
| Border | `#3A3350` | Thin component borders/dividers |

Styling tokens (FlatLaf `UIManager` keys):
- Corner radius ~8px (`Button.arc`, `Component.arc`, `TextComponent.arc`, `ProgressBar.arc`)
- Focus width 2px with accent glow (`Component.focusWidth`, `Component.focusColor`)
- Modern flat scrollbars (`ScrollBar.thumbArc`, `ScrollBar.trackArc`, hover/pressed colors)
- Comfortable spacing (`Button.margin`, `Component.minimumWidth`)
- Default font: Inter (FlatLaf bundled), preserving the user-configurable GUI size

## Client Implementation (`Mage.Client`)

1. **Dependency:** add FlatLaf to `Mage.Client/pom.xml`.
2. **L&F init:** in `mage/client/util/gui/GuiDisplayUtil.java#refreshThemeSettings()`, replace the Nimbus `UIManager.setLookAndFeel(...)` call with FlatLaf, and translate the current `ThemeType` color set into FlatLaf keys instead of Nimbus keys.
   - Preserve the theme-switching mechanism: all six existing themes must still apply (mapped onto FlatLaf), and the live preview in the preferences dialog must still work.
3. **New theme:** add `ARCANE` to the `ThemeType` enum (`mage/client/themes/ThemeType.java`) using the palette above; make it the **default** theme for new users while leaving existing user-selected themes intact.
4. **Color-read audit:** `ThemeType` color getters (e.g. `getNimbusBase`, `getPlayerPanel_*`, card-icon colors) are read directly by custom painters (player panels, card icons, helper panels). After the swap, verify these still produce sensible colors under the new themes; the getters stay but are reinterpreted as semantic colors. Spot-fix any painter that assumed Nimbus-specific behavior.
5. **Game board:** unchanged. Verify it still renders correctly with the new L&F active (it should, as it is custom-painted, but tight fixed-size panels around it need a visual pass).

## Launcher Implementation (`DarrellBest/Launcher`, separate repo)

1. **Dependency:** add FlatLaf to the launcher `pom.xml`.
2. **L&F swap:** replace Metal (`XMageLauncher.java:492`) with FlatLaf dark + the Arcane accent/font.
3. **Button restyling:** rounded buttons; the primary launch action(s) use the gold accent (`Button.default` styling); secondary actions use standard styling.
4. **Console & background:** keep the background image; add a subtle dark overlay for legibility; restyle the console `JTextArea` (dark surface, readable mono text) instead of pure `Color.BLACK`.
5. **Dialogs:** `SettingsDialog` and `AboutDialog` inherit the theme automatically; verify spacing/fonts read well.
6. Preserve all launcher behavior: download/update flow, server/client launching, settings persistence, GUI-size config.

## Scope Boundaries

**In scope:** L&F replacement, Arcane theme, global styling polish (corners, spacing, scrollbars, focus, accents), default UI font, mapping existing themes onto FlatLaf.

**Out of scope (would require a rewrite or change behavior):** animations/transitions, fully custom-painted window chrome, particle/visual effects, layout restructuring, the in-game card/battlefield rendering, any change to game logic, networking, or persisted settings semantics.

## Risks & Mitigations

- **Hardcoded color reads:** some components read `ThemeType` colors directly. *Mitigation:* keep the getters, reinterpret as semantic tokens, visually verify affected painters (player panels, card icons, helper/feedback panels).
- **Tight fixed-size NetBeans layouts:** FlatLaf's slightly different metrics could clip a few cramped panels. *Mitigation:* visual pass over high-traffic screens (login/connect, table lobby, deck editor, new-table dialog, game HUD), spot-fix sizing without restructuring layouts.
- **Theme regressions:** the five non-Arcane themes must still look acceptable post-swap. *Mitigation:* sanity-check each theme after remapping; the Arcane theme is the showcase, others must remain functional and not visually broken.
- **Two-repo coordination:** launcher is a separate repo. *Mitigation:* client first (the bulk), launcher second; the Arcane palette is the shared contract between them.

## Success Criteria

- Both apps launch and run with **identical functionality** to before.
- The default experience is the dark Arcane theme; the launcher and client share a coherent visual identity.
- The "blocky 90s" Metal/Nimbus styling is gone: flat surfaces, rounded controls, modern scrollbars, accent focus, readable modern font.
- All six client themes still apply via the theme switcher; live preview still works.
- No new compilation warnings for Java 8; no behavior or layout regressions found in the visual pass over high-traffic screens.

## Build / Ordering

1. Client: dependency → L&F swap + theme remap → Arcane theme → color-read audit → visual pass.
2. Launcher: dependency → L&F swap → button/console/background restyle → dialog verification.

Each repo gets its own implementation plan; this spec is the shared source of truth.
