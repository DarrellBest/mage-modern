# Client UI Modernization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Re-skin the XMage client (`Mage.Client`) from Nimbus to FlatLaf with a new dark "Arcane" theme, modernizing all UI chrome without changing any functionality.

**Architecture:** Swap the look-and-feel engine inside the existing `GuiDisplayUtil.refreshThemeSettings()` method from Nimbus to FlatLaf (`FlatDarkLaf`/`FlatLightLaf` chosen by theme darkness), map the existing `ThemeType` color set onto FlatLaf `UIManager` keys, add `ARCANE` as a new `ThemeType`, and make it the default. The theme-switching mechanism and all six existing themes keep working. The game board (custom-painted card/battlefield art) is untouched.

**Tech Stack:** Java 8, Maven, Swing, FlatLaf 3.7.1 (`com.formdev:flatlaf`), FlatLaf Inter font 4.1 (`com.formdev:flatlaf-fonts-inter`).

**Verification model:** This is a visual re-skin. Each task's gate is **(a) the module compiles** and **(b) the client launches to the login screen with no L&F exceptions**, plus periodic **visual checkpoints** where you launch the app and confirm the look. There are no unit-testable behaviors here.

**Reference spec:** `docs/superpowers/specs/2026-05-25-ui-modernization-design.md`

---

## Prerequisites (one-time)

- [ ] **Step 0a: Confirm a baseline full build works BEFORE any change**

Run from repo root:
```bash
mvn -q install -DskipTests -pl Mage.Common,Mage,Mage.Client -am
```
Expected: `BUILD SUCCESS`. This compiles the client and its core dependencies. If this fails before you change anything, stop and fix the environment first — do not attribute it to your changes later.

- [ ] **Step 0b: Capture how to launch the client for visual checks**

After a successful build, the client main class is `mage.client.MageFrame`. Launch it (it opens to the login/connect screen without needing a server):
```bash
cd Mage.Client && mvn -q exec:java -Dexec.mainClass=mage.client.MageFrame 2>/dev/null || \
  java -cp "target/classes:target/dependency/*" mage.client.MageFrame
```
If `exec:java` is not configured, build the assembly (`mvn -pl Mage.Client package assembly:single -DskipTests`) and run the jar in `Mage.Client/target/`. Note the working launch command for reuse in later visual checkpoints. (You may also use the project's `/run` skill.)

---

## Task 1: Add FlatLaf dependencies

**Files:**
- Modify: `Mage.Client/pom.xml` (dependencies section)

- [ ] **Step 1: Add the FlatLaf dependencies**

In `Mage.Client/pom.xml`, inside the existing `<dependencies>` element, add:
```xml
        <dependency>
            <groupId>com.formdev</groupId>
            <artifactId>flatlaf</artifactId>
            <version>3.7.1</version>
        </dependency>
        <dependency>
            <groupId>com.formdev</groupId>
            <artifactId>flatlaf-fonts-inter</artifactId>
            <version>4.1</version>
        </dependency>
```

- [ ] **Step 2: Verify the dependency resolves and the module still compiles**

Run from repo root:
```bash
mvn -q -pl Mage.Client -am compile
```
Expected: `BUILD SUCCESS`, FlatLaf jars downloaded to the local Maven repo.

- [ ] **Step 3: Commit**

```bash
git add Mage.Client/pom.xml
git commit -m "build(client): add FlatLaf and Inter font dependencies"
```

---

## Task 2: Add a luminance helper to decide light vs dark base L&F

**Files:**
- Modify: `Mage.Client/src/main/java/mage/client/util/gui/GuiDisplayUtil.java`

We need to pick `FlatDarkLaf` for dark themes and `FlatLightLaf` for light ones, based on the theme's window-background color (`control`).

- [ ] **Step 1: Add the helper method**

In `GuiDisplayUtil.java`, add this private static method (place it directly above `refreshThemeSettings()`):
```java
    /**
     * Decide whether a theme should use the dark or light FlatLaf base,
     * based on the perceived luminance of its window background color.
     */
    private static boolean isThemeDark(java.awt.Color background) {
        if (background == null) {
            return true;
        }
        // Rec. 601 luma; < 128 is a dark surface
        double luma = 0.299 * background.getRed()
                + 0.587 * background.getGreen()
                + 0.114 * background.getBlue();
        return luma < 128.0;
    }
```

- [ ] **Step 2: Verify compile**

```bash
mvn -q -pl Mage.Client -am compile
```
Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Commit**

```bash
git add Mage.Client/src/main/java/mage/client/util/gui/GuiDisplayUtil.java
git commit -m "feat(client): add theme luminance helper for FlatLaf base selection"
```

---

## Task 3: Swap Nimbus → FlatLaf in refreshThemeSettings

**Files:**
- Modify: `Mage.Client/src/main/java/mage/client/util/gui/GuiDisplayUtil.java` (the `refreshThemeSettings()` method)

The current method (around lines 473–520) calls `UIManager.setLookAndFeel("javax.swing.plaf.nimbus.NimbusLookAndFeel")` and then sets Nimbus-specific keys (`nimbusBlueGrey`, `control`, `nimbusLightBackground`, `info`, `nimbusBase`, `text`). We replace the Nimbus setup and the Nimbus-key block with FlatLaf setup + FlatLaf keys, while **keeping** the existing "re-render existing components" loops below it unchanged.

- [ ] **Step 1: Add the FlatLaf imports**

At the top of `GuiDisplayUtil.java`, with the other imports, add:
```java
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
```

- [ ] **Step 2: Replace the Nimbus setup block**

Find this block (the `try { UIManager.setLookAndFeel("javax.swing.plaf.nimbus.NimbusLookAndFeel"); ... }` catch) and replace it with:
```java
        // apply FlatLaf, choosing dark/light base from the current theme
        mage.client.themes.ThemeType theme = PreferencesDialog.getCurrentTheme();
        try {
            if (isThemeDark(theme.getControl())) {
                UIManager.setLookAndFeel(new FlatDarkLaf());
            } else {
                UIManager.setLookAndFeel(new FlatLightLaf());
            }
        } catch (UnsupportedLookAndFeelException e) {
            logger.error("Can't apply current theme: " + theme + " - " + e, e);
        }
```

- [ ] **Step 3: Replace the Nimbus-key block with FlatLaf keys**

Find the block that sets `UIManager.put("nimbusBlueGrey", ...)` through `UIManager.put("text", ...)` and replace ALL of those `UIManager.put(...)` lines with:
```java
        // map theme palette onto FlatLaf semantic keys
        UIManager.put("Component.accentColor", theme.getNimbusBase());   // selection/accent
        UIManager.put("Component.focusColor", theme.getNimbusBase());    // focus ring
        UIManager.put("background", theme.getControl());                 // window/panel bg
        UIManager.put("Panel.background", theme.getControl());
        UIManager.put("TextField.background", theme.getNimbusLightBackground());
        UIManager.put("Table.background", theme.getNimbusLightBackground());
        UIManager.put("List.background", theme.getNimbusLightBackground());
        UIManager.put("ToolTip.background", theme.getInfo());
        UIManager.put("foreground", theme.getTextColor());
        UIManager.put("text", theme.getTextColor());

        // modern flat styling shared by all themes
        UIManager.put("Component.arc", 8);
        UIManager.put("Button.arc", 8);
        UIManager.put("ProgressBar.arc", 8);
        UIManager.put("TextComponent.arc", 6);
        UIManager.put("ScrollBar.thumbArc", 999);
        UIManager.put("ScrollBar.thumbInsets", new java.awt.Insets(2, 2, 2, 2));
        UIManager.put("ScrollBar.width", 12);
        UIManager.put("Component.focusWidth", 1);
        UIManager.put("TabbedPane.tabSeparatorsFullHeight", false);
        UIManager.put("TitlePane.unifiedBackground", true);
```

Leave everything below (the `for (Frame frame : Frame.getFrames())` loop and the `MageComponents` refresh loop) exactly as-is.

- [ ] **Step 4: Verify compile**

```bash
mvn -q -pl Mage.Client -am compile
```
Expected: `BUILD SUCCESS`.

- [ ] **Step 5: VISUAL CHECKPOINT — launch and confirm FlatLaf is active**

Launch the client (Step 0b command). Confirm:
- The app opens to the login/connect screen with no stack trace in the console.
- Windows/buttons now look flat (FlatLaf), not the raised Nimbus/Metal style.
- The current theme is still the previously-selected one (likely "Default"/light) — that's expected; the dark Arcane theme arrives in Task 4.
If you see exceptions about the L&F, fix before proceeding.

- [ ] **Step 6: Commit**

```bash
git add Mage.Client/src/main/java/mage/client/util/gui/GuiDisplayUtil.java
git commit -m "feat(client): replace Nimbus look-and-feel with FlatLaf"
```

---

## Task 4: Add the ARCANE theme

**Files:**
- Modify: `Mage.Client/src/main/java/mage/client/themes/ThemeType.java`

The `ThemeType` enum constructor takes 23 args in this order: `name, path, hasBackground, hasLoginBackground, hasBattleBackground, hasSkipButtons, hasPhaseIcons, hasWinLossImages, shortcutsVisibleForSkipButtons, nimbusBlueGrey, control, nimbusLightBackground, info, nimbusBase, mageToolbar, playerPanel_inactiveBackgroundColor, playerPanel_activeBackgroundColor, playerPanel_deadBackgroundColor, deckEditorToolbarBackgroundColor, cardIconsFillColor, cardIconsStrokeColor, cardIconsTextColor, textColor`. We mirror `DEFAULT`'s booleans/`path` (so background/image behavior matches DEFAULT exactly) and supply the Arcane palette.

- [ ] **Step 1: Add the ARCANE enum constant**

In `ThemeType.java`, add this as the **first** enum constant (immediately before `DEFAULT(`):
```java
    ARCANE("Arcane",                       // name
            "",                            // path (reuse default backgrounds)
            true,                          // hasBackground
            false,                         // hasLoginBackground
            true,                          // hasBattleBackground
            true,                          // hasSkipButtons
            true,                          // hasPhaseIcons
            true,                          // hasWinLossImages
            true,                          // shortcutsVisibleForSkipButtons
            new Color(58, 51, 80),         // nimbusBlueGrey  -> borders/buttons  #3A3350
            new Color(26, 22, 38),         // control         -> window bg        #1A1626 (dark => FlatDarkLaf)
            new Color(44, 36, 64),         // nimbusLightBackground -> inputs/rows #2C2440
            new Color(34, 28, 51),         // info            -> tooltips         #221C33
            new Color(61, 214, 196),       // nimbusBase      -> accent (teal)    #3DD6C4
            null,                          // mageToolbar
            new Color(34, 28, 51, 200),    // playerPanel_inactiveBackgroundColor
            new Color(46, 92, 85, 200),    // playerPanel_activeBackgroundColor (teal-tinted)
            new Color(58, 36, 51, 200),    // playerPanel_deadBackgroundColor
            new Color(34, 28, 51, 150),    // deckEditorToolbarBackgroundColor
            new Color(58, 51, 80),         // cardIconsFillColor   #3A3350
            Color.black,                   // cardIconsStrokeColor
            new Color(61, 214, 196),       // cardIconsTextColor   accent teal
            new Color(236, 232, 245)       // textColor            off-white #ECE8F5
    ),
```

- [ ] **Step 2: Verify compile**

```bash
mvn -q -pl Mage.Client -am compile
```
Expected: `BUILD SUCCESS`. (If the count of constructor args is wrong you'll get a "no suitable constructor" error — re-check the 23 values above against the constructor.)

- [ ] **Step 3: VISUAL CHECKPOINT — select Arcane in Preferences**

Launch the client. Open Preferences → Themes, select **"Arcane"**, apply. Confirm:
- Surfaces turn deep charcoal-purple, text is readable off-white, accent/selection is teal.
- No exceptions; the preview/apply works (this exercises the theme-switch path through `refreshThemeSettings`).

- [ ] **Step 4: Commit**

```bash
git add Mage.Client/src/main/java/mage/client/themes/ThemeType.java
git commit -m "feat(client): add Arcane dark theme"
```

---

## Task 5: Make Arcane the default for new users

**Files:**
- Modify: `Mage.Client/src/main/java/mage/client/dialog/PreferencesDialog.java` (around line 630)

The default theme is resolved by `currentTheme = ThemeType.valueByName(getCachedValue(KEY_THEME, "Default"));`. Changing the fallback string to `"Arcane"` makes new users default to Arcane while preserving any saved preference for existing users.

- [ ] **Step 1: Change the default fallback**

Find:
```java
        currentTheme = ThemeType.valueByName(getCachedValue(KEY_THEME, "Default"));
```
Replace with:
```java
        currentTheme = ThemeType.valueByName(getCachedValue(KEY_THEME, "Arcane"));
```

- [ ] **Step 2: Verify compile**

```bash
mvn -q -pl Mage.Client -am compile
```
Expected: `BUILD SUCCESS`.

- [ ] **Step 3: VISUAL CHECKPOINT — fresh-profile default**

To simulate a new user, temporarily clear the saved theme preference (or run with a fresh config) and launch. Confirm the client comes up in the Arcane dark theme by default. (If you have an existing saved theme, it should be respected — that's correct behavior.)

- [ ] **Step 4: Commit**

```bash
git add Mage.Client/src/main/java/mage/client/dialog/PreferencesDialog.java
git commit -m "feat(client): default new users to the Arcane theme"
```

---

## Task 6: Color-read audit + visual pass over high-traffic screens

**Files:**
- Potentially modify (only if a regression is found): custom painters that read `ThemeType` colors directly, e.g. player panels, card icons, helper/feedback panels.

This task finds and fixes any spot where the Nimbus→FlatLaf swap produced an unreadable or broken color. We do NOT restructure layouts — only fix colors/contrast.

- [ ] **Step 1: Enumerate direct theme-color readers**

```bash
grep -rn "getCurrentTheme()\.\(getNimbusBase\|getControl\|getNimbusLightBackground\|getNimbusBlueGrey\|getInfo\|getTextColor\|getPlayerPanel\)" Mage.Client/src/main/java --include=*.java
```
Note each location — these read theme colors outside the L&F and must look correct under Arcane.

- [ ] **Step 2: VISUAL CHECKPOINT — walk the high-traffic screens in Arcane**

Launch in Arcane and visually inspect, looking specifically for unreadable text (dark-on-dark / light-on-light), invisible borders, or white "blowout" panels:
- Login / connect screen
- Main table lobby (tables list, chat)
- New table / new tournament dialog
- Deck editor (toolbar, card list, deck area)
- A game (start a game vs AI): player panels (active/inactive/dead states), phase bar, hand/stack, card hint tooltips, card icons

- [ ] **Step 3: Fix only genuine regressions**

For each unreadable/broken spot, adjust the relevant `ThemeType` Arcane color (preferred — keeps the fix in one place) or, if a painter hardcodes an assumption, fix that painter's color read. Re-launch and re-verify after each fix. Keep changes minimal and color-only.

- [ ] **Step 4: Verify compile (if any code changed)**

```bash
mvn -q -pl Mage.Client -am compile
```
Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit (if any changes were made)**

```bash
git add -A Mage.Client/src/main/java
git commit -m "fix(client): contrast/readability fixes for Arcane theme"
```
If no changes were needed, record that the visual pass found no regressions and skip the commit.

---

## Task 7: Apply the Inter UI font (optional polish, gated)

**Files:**
- Modify: `Mage.Client/src/main/java/mage/client/util/gui/GuiDisplayUtil.java` (`refreshThemeSettings()`)

A modern UI font is part of removing the "90s" feel. This is gated separately because changing the default font can subtly change text metrics in the 65 fixed-size NetBeans layouts. If the visual checkpoint shows clipping, revert this one task.

- [ ] **Step 1: Add the import**

```java
import com.formdev.flatlaf.fonts.inter.FlatInterFont;
```

- [ ] **Step 2: Install Inter and set it as the default family**

In `refreshThemeSettings()`, immediately AFTER the `UIManager.setLookAndFeel(...)` try/catch block (Task 3 Step 2) and BEFORE the FlatLaf key block, add:
```java
        // modern UI font (keeps existing size via FlatLaf's default font size)
        FlatInterFont.installLazy();
        com.formdev.flatlaf.FlatLaf.setPreferredFontFamily(FlatInterFont.FAMILY);
```

- [ ] **Step 3: Verify compile**

```bash
mvn -q -pl Mage.Client -am compile
```
Expected: `BUILD SUCCESS`.

- [ ] **Step 4: VISUAL CHECKPOINT — font + clipping check**

Launch in Arcane. Confirm the UI font looks like Inter (cleaner than the default) and that **no labels/buttons are clipped** in the high-traffic screens from Task 6. If you see clipping you can't resolve with minor sizing, **revert this task** (`git revert` the commit or undo the edits) — the re-skin stands on its own without the custom font.

- [ ] **Step 5: Commit**

```bash
git add Mage.Client/src/main/java/mage/client/util/gui/GuiDisplayUtil.java
git commit -m "feat(client): use Inter as the default UI font"
```

---

## Task 8: Final full-build verification

- [ ] **Step 1: Full client build with the assembly**

```bash
mvn -q -pl Mage.Common,Mage,Mage.Client -am install -DskipTests
```
Expected: `BUILD SUCCESS`.

- [ ] **Step 2: Final end-to-end visual confirmation**

Launch the built client one more time. Confirm: defaults to Arcane, all six themes still selectable and apply without error (spot-check Default + Carbon Fiber + one more), no console exceptions, and the game board renders correctly in a quick AI game.

- [ ] **Step 3: Done**

The client re-skin is complete. Proceed to the launcher plan (`/home/dbest/projects/Launcher/docs/superpowers/plans/2026-05-25-launcher-ui-modernization.md`).

---

## Notes for the implementer

- **Never change behavior.** Every edit in this plan is styling/color/L&F only. If a step seems to require touching an event handler, layout graph, networking, or game logic, stop — it's out of scope.
- **One place for color truth:** prefer fixing colors in `ThemeType.ARCANE` over editing individual painters.
- **The game board is image art** — it won't be "modernized" by the L&F and shouldn't be; only the chrome around it changes.
