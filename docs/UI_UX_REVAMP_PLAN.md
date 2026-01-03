# CameraInterceptor UI/UX Revamp Plan

> **Document Status:** 🟢 Living Document  
> **Created:** January 3, 2026  
> **Last Updated:** January 3, 2026  
> **Validation Command:** `gradle assembleDebug`

---

## Document Guidelines

### Keeping This Document Living

This document serves as the **single source of truth** for the UI/UX revamp effort. It must be:

1. **Updated after each phase completion** — Mark phases as complete, add notes about deviations or learnings
2. **Validated before commits** — Every phase must pass `gradle assembleDebug` before being committed
3. **Committed phase by phase** — Each phase gets its own commit(s) with clear commit messages referencing this plan
4. **Reviewed for accuracy** — If implementation differs from plan, update the plan to reflect reality

### Commit Convention

```
feat(ui): Phase X.Y - <brief description>

Implements <component/feature> as part of UI/UX revamp.
See docs/UI_UX_REVAMP_PLAN.md Phase X.Y

- Change 1
- Change 2
- Change 3
```

### Validation Workflow

```bash
# After completing any task:
gradle assembleDebug

# If successful, stage and commit:
git add .
git commit -m "feat(ui): Phase X.Y - <description>"

# If failed, fix errors before proceeding
```

---

## Project Context

### Current State Analysis

**App Purpose:** CameraInterceptor is an Xposed/LSPosed module that intercepts camera access in Android apps, allowing users to inject custom images instead of live camera feed.

**Current UI Architecture:**
| Component | Technology | Issues |
|-----------|------------|--------|
| Main Screen | `PreferenceFragment` (deprecated) | No custom styling, system defaults |
| Image Picker | Activity with Dialog theme | Fixed dimensions, poor UX |
| App Selection | `ListView` with custom adapter | No search, deprecated AsyncTask |
| Log Viewer | Basic `ScrollView` + `TextView` | No syntax highlighting, manual refresh |

**Resource Inventory (Before Revamp):**
| Resource Type | Count | Notes |
|---------------|-------|-------|
| Layout XML files | 4 | All use `LinearLayout`, hardcoded dimensions |
| Activities | 4 | Using deprecated APIs |
| Fragments | 1 | Deprecated `PreferenceFragment` |
| themes.xml | 0 | ❌ Missing |
| colors.xml | 0 | ❌ Missing |
| dimens.xml | 0 | ❌ Missing |
| styles.xml | 0 | ❌ Missing |
| Custom drawables | 0 | ❌ Missing |
| App icon | 0 | ❌ Missing |

**Hardcoded Values Found:**
- Colors: `#EEEEEE`, `#000000`, `#FFFFFF` (in layouts)
- Dimensions: `200dp`, `48dp`, `16dp`, `8dp` (scattered)
- Strings: Some UI text not in `strings.xml`

### Pain Points Identified

1. **No visual identity** — No app icon, no brand colors, no consistent theme
2. **Deprecated APIs** — `PreferenceFragment`, `AsyncTask`, basic `ListView`
3. **No responsive design** — Fixed dimensions, no landscape/tablet layouts
4. **Poor accessibility** — Missing `contentDescription`, small touch targets
5. **No user guidance** — No onboarding, no contextual help, no documentation
6. **Minimal feedback** — Only Toast messages, no loading states, no confirmations
7. **No dark theme** — No night mode support

---

## Revamp Goals

### Primary Objectives

- [ ] **Professional appearance** — Material Design 3 compliance, consistent branding
- [ ] **Intuitive UX** — Clear navigation, contextual help, proper feedback
- [ ] **Device responsiveness** — Works on phones, tablets, portrait, landscape
- [ ] **Accessibility** — WCAG compliance, TalkBack support, proper contrast
- [ ] **User documentation** — In-app onboarding, help screens, tooltips

### Success Criteria

| Metric | Target |
|--------|--------|
| Build success | `gradle assembleDebug` passes after each phase |
| Touch targets | Minimum 48dp on all interactive elements |
| Color contrast | Minimum 4.5:1 ratio for text |
| Loading feedback | All async operations show progress indicator |
| Help coverage | Every toggle/setting has accessible explanation |

---

## Phase 1: Foundation & Design System

**Status:** ✅ Completed (January 3, 2026)  
**Estimated Files:** 5-7 new resource files  
**Dependencies:** None

### 1.1 Create Color Palette

**File:** `app/src/main/res/values/colors.xml`

Define Material Design 3 color tokens:
- Primary, Secondary, Tertiary color families
- Surface and background colors
- Error, warning, success semantic colors
- On-colors for text/icons on each surface

```xml
<!-- Example structure -->
<color name="md_theme_light_primary">#6750A4</color>
<color name="md_theme_light_onPrimary">#FFFFFF</color>
<color name="md_theme_light_surface">#FFFBFE</color>
<!-- ... full palette -->
```

**Validation:** File created, no XML syntax errors

---

### 1.2 Create Dimension Resources

**File:** `app/src/main/res/values/dimens.xml`

Establish spacing scale and component sizes:
- Spacing: 4dp, 8dp, 12dp, 16dp, 24dp, 32dp, 48dp
- Icon sizes: 24dp (small), 40dp (medium), 48dp (large)
- Touch targets: 48dp minimum
- Corner radii: 4dp (small), 8dp (medium), 16dp (large), 28dp (full)
- Elevation levels: 1dp, 3dp, 6dp, 8dp, 12dp

**Validation:** File created, dimensions can be referenced

---

### 1.3 Create Theme Infrastructure

**Files:** 
- `app/src/main/res/values/themes.xml`
- `app/src/main/res/values-night/themes.xml`

Configure Material 3 theme with:
- Parent: `Theme.Material3.DayNight.NoActionBar`
- Custom color attributes mapped to palette
- Default typography styles
- Shape theme (corner radii)

**Validation:** App builds, theme applies without crashes

---

### 1.4 Create Common Styles

**File:** `app/src/main/res/values/styles.xml`

Define reusable component styles:
- Button styles (filled, outlined, text)
- Card styles
- Text appearance overrides
- Toolbar style

**Validation:** Styles can be referenced in layouts

---

### 1.5 Add Material Components Dependency

**File:** `app/build.gradle`

Add/update dependency:
```groovy
implementation 'com.google.android.material:material:1.11.0'
```

Ensure `compileSdk` and `targetSdk` are adequate (34+).

**Validation:** `gradle assembleDebug` succeeds, no dependency conflicts

---

### 1.6 Create App Icon

**Files:**
- `app/src/main/res/mipmap-*/ic_launcher.webp`
- `app/src/main/res/mipmap-*/ic_launcher_round.webp`
- `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
- `app/src/main/res/drawable/ic_launcher_foreground.xml`
- `app/src/main/res/values/ic_launcher_background.xml`

Design adaptive icon with:
- Camera-related iconography
- Brand colors from palette
- Proper safe zone compliance

**Validation:** Icon appears in launcher, no visual cropping issues

---

### Phase 1 Completion Checklist

- [x] `colors.xml` created with full Material 3 palette
- [x] `dimens.xml` created with spacing scale
- [x] `themes.xml` (day) created and applied
- [x] `themes.xml` (night) created for dark mode
- [x] `styles.xml` created with common component styles
- [x] Material Components dependency added
- [x] App icon assets created
- [x] `gradle assembleDebug` passes
- [ ] Changes committed: `feat(ui): Phase 1 - Foundation & Design System`

---

## Phase 2: Main Screen Modernization

**Status:** ✅ Completed (January 3, 2026)  
**Estimated Files:** 3-5 modified files  
**Dependencies:** Phase 1 complete

### 2.1 Migrate to AndroidX Preferences

**Files:**
- `app/build.gradle` — Add `androidx.preference:preference:1.2.1`
- `app/src/main/java/com/camerainterceptor/ui/SettingsActivity.java` — Replace `PreferenceFragment` with `PreferenceFragmentCompat`
- `app/src/main/res/xml/preferences.xml` — Update namespace if needed

**Changes:**
- Extend `PreferenceFragmentCompat` instead of `PreferenceFragment`
- Use `onCreatePreferences()` instead of `onCreate()`
- Update imports to `androidx.preference.*`

**Validation:** Settings screen loads, all preferences functional

---

### 2.2 Add Toolbar Layout

**File:** `app/src/main/res/layout/activity_settings.xml` (new)

Create layout with:
- `CoordinatorLayout` root
- `MaterialToolbar` with app title
- `FragmentContainerView` for preferences

**File:** `app/src/main/java/com/camerainterceptor/ui/SettingsActivity.java`

Update to:
- Use `setContentView()` with new layout
- Set up toolbar with `setSupportActionBar()`

**Validation:** Toolbar appears, preferences display below

---

### 2.3 Add Preference Icons

**Files:**
- `app/src/main/res/drawable/ic_image.xml`
- `app/src/main/res/drawable/ic_toggle.xml`
- `app/src/main/res/drawable/ic_notifications.xml`
- `app/src/main/res/drawable/ic_apps.xml`
- `app/src/main/res/drawable/ic_logs.xml`
- `app/src/main/res/drawable/ic_help.xml`
- `app/src/main/res/drawable/ic_info.xml`

**File:** `app/src/main/res/xml/preferences.xml`

Add `android:icon` attribute to each preference.

**Validation:** Icons display next to each preference item

---

### 2.4 Style Preference Categories

**File:** `app/src/main/res/values/styles.xml`

Add preference-specific styles:
- Category header style
- Preference item padding
- Switch preference styling

**Validation:** Categories visually distinct, proper spacing

---

### Phase 2 Completion Checklist

- [x] AndroidX Preference dependency added
- [x] `SettingsActivity` migrated to `PreferenceFragmentCompat`
- [x] Custom toolbar layout created and integrated
- [x] Preference icons added (vector drawables)
- [x] Preference categories styled
- [x] Dark theme works on settings screen
- [x] `gradle assembleDebug` passes
- [x] Changes committed: `feat(ui): Phase 2 - Main Screen Modernization`

---

## Phase 3: Image Picker Redesign

**Status:** ✅ Completed (January 3, 2026)  
**Estimated Files:** 2-4 modified/new files  
**Dependencies:** Phase 1 complete

### 3.1 Convert to BottomSheetDialogFragment

**Files:**
- `app/src/main/java/com/camerainterceptor/ui/ImagePickerFragment.java` (new)
- `app/src/main/res/layout/fragment_image_picker.xml` (new)
- `app/src/main/java/com/camerainterceptor/ui/ImagePickerActivity.java` (delete or deprecate)

**Design:**
- Rounded top corners (28dp radius)
- Drag handle indicator
- Swipe-to-dismiss behavior
- Peek height showing primary action

**Validation:** Bottom sheet opens from settings, can select/clear image

---

### 3.2 Redesign Image Preview

**File:** `app/src/main/res/layout/fragment_image_picker.xml`

Components:
- `ShapeableImageView` with rounded corners (16dp)
- Placeholder with camera icon when no image
- Responsive sizing (match_parent width, aspect ratio constrained)
- Surface color background with elevation

**Validation:** Preview displays correctly, placeholder shows when empty

---

### 3.3 Upgrade Action Buttons

**File:** `app/src/main/res/layout/fragment_image_picker.xml`

Replace `Button` with `MaterialButton`:
- Primary action: Filled button with icon (Select Image)
- Secondary action: Outlined button (Clear Selection)
- Proper spacing (16dp between buttons)
- Full-width on mobile, side-by-side on tablet

**Validation:** Buttons styled correctly, actions work

---

### 3.4 Add Loading State

**File:** `app/src/main/java/com/camerainterceptor/ui/ImagePickerFragment.java`

Implement:
- `CircularProgressIndicator` while image loads
- Disabled buttons during loading
- Error state with retry option

**Validation:** Progress shows during image selection, graceful error handling

---

### Phase 3 Completion Checklist

- [x] `ImagePickerFragment` (BottomSheetDialogFragment) created
- [x] Old `ImagePickerActivity` removed or deprecated
- [x] Image preview with rounded corners and placeholder
- [x] MaterialButtons with icons
- [x] Loading and error states
- [x] Swipe-to-dismiss works
- [x] `gradle assembleDebug` passes
- [x] Changes committed: `feat(ui): Phase 3 - Image Picker Redesign`

---

## Phase 4: App Selection Screen Overhaul

**Status:** ✅ Completed (January 3, 2026)  
**Estimated Files:** 4-6 modified files  
**Dependencies:** Phase 1 complete

### 4.1 Replace ListView with RecyclerView

**Files:**
- `app/src/main/res/layout/activity_app_selection.xml`
- `app/src/main/java/com/camerainterceptor/ui/AppSelectionActivity.java`
- `app/src/main/java/com/camerainterceptor/ui/AppListAdapter.java` (new)
- `app/src/main/java/com/camerainterceptor/ui/AppViewHolder.java` (new)

**Implementation:**
- `RecyclerView` with `LinearLayoutManager`
- `ListAdapter` with `DiffUtil.ItemCallback` for efficient updates
- `ViewBinding` for view references

**Validation:** App list displays with RecyclerView, scrolling smooth

---

### 4.2 Add Search Functionality

**Files:**
- `app/src/main/res/menu/menu_app_selection.xml` (new)
- `app/src/main/java/com/camerainterceptor/ui/AppSelectionActivity.java`

**Implementation:**
- `SearchView` in toolbar
- Real-time filtering as user types
- Filter by app name AND package name
- Clear search button
- Chip filters: "User Apps" / "System Apps" / "All"

**Validation:** Search filters list, filter chips work

---

### 4.3 Redesign List Item

**File:** `app/src/main/res/layout/item_app.xml` (rename from `app_list_item.xml`)

**New Layout:**
```
┌─────────────────────────────────────────────────┐
│ [Icon 48dp]  App Name                    [Switch]│
│              com.package.name                    │
│              Mode: [Chip: Photo/Video/Both]      │
└─────────────────────────────────────────────────┘
```

Components:
- `ConstraintLayout` root
- `ShapeableImageView` for app icon (rounded square)
- `MaterialTextView` for app name (titleMedium)
- `MaterialTextView` for package (bodySmall, secondary color)
- `MaterialSwitch` for enable/disable
- `ChipGroup` for mode selection

**Validation:** List items display correctly, all interactive elements work

---

### 4.4 Add Empty and Loading States

**Files:**
- `app/src/main/res/layout/activity_app_selection.xml`
- `app/src/main/res/layout/layout_empty_state.xml` (new)
- `app/src/main/res/drawable/il_empty_apps.xml` (new)

**Empty State:**
- Illustration/icon
- "No apps found" message
- Suggestion text ("Try adjusting your search")

**Loading State:**
- Shimmer placeholder or `CircularProgressIndicator`
- Skeleton list items while loading

**Validation:** Empty state shows when search yields no results, loading shows during app enumeration

---

### 4.5 Replace Save Button with FAB

**Files:**
- `app/src/main/res/layout/activity_app_selection.xml`
- `app/src/main/java/com/camerainterceptor/ui/AppSelectionActivity.java`

**Implementation:**
- `ExtendedFloatingActionButton` for "Save" action
- Collapses to icon-only on scroll down
- Extends on scroll up
- Bottom-end positioning with proper margin

**Validation:** FAB visible, save action works, scroll behavior correct

---

### Phase 4 Completion Checklist

- [x] RecyclerView replaces ListView
- [x] Adapter with DiffUtil implemented
- [x] Search functionality in toolbar
- [x] Filter chips (User/System/All apps)
- [x] List item redesigned with ConstraintLayout
- [x] MaterialSwitch and mode chips functional
- [x] Empty state with illustration
- [x] Loading state (shimmer or progress)
- [x] ExtendedFAB for save action
- [x] `gradle assembleDebug` passes
- [x] Changes committed: `feat(ui): Phase 4 - App Selection Overhaul`

---

## Phase 5: Log Viewer Enhancement

**Status:** ✅ Completed (January 3, 2026)  
**Estimated Files:** 3-4 modified files  
**Dependencies:** Phase 1 complete

### 5.1 Redesign Layout with ConstraintLayout

**File:** `app/src/main/res/layout/activity_log_viewer.xml`

**New Structure:**
- `CoordinatorLayout` root
- `MaterialToolbar` with title and search action
- `SwipeRefreshLayout` wrapping content
- `RecyclerView` for log entries (better performance than TextView)
- `BottomAppBar` with actions

**Validation:** Layout renders, no overflow issues

---

### 5.2 Add Syntax Highlighting

**Files:**
- `app/src/main/java/com/camerainterceptor/ui/LogViewerActivity.java`
- `app/src/main/java/com/camerainterceptor/ui/LogEntryAdapter.java` (new)
- `app/src/main/res/layout/item_log_entry.xml` (new)

**Color Coding:**
| Level | Color |
|-------|-------|
| ERROR | `?colorError` (red) |
| WARN | `#FF9800` (amber) |
| INFO | `?colorPrimary` (brand) |
| DEBUG | `?colorOnSurfaceVariant` (gray) |

**Implementation:**
- Parse log lines to extract level
- Apply `Span` or use RecyclerView with typed ViewHolders
- Monospace font preserved

**Validation:** Log levels visually distinct, readable

---

### 5.3 Implement Pull-to-Refresh

**Files:**
- `app/src/main/res/layout/activity_log_viewer.xml`
- `app/src/main/java/com/camerainterceptor/ui/LogViewerActivity.java`

**Implementation:**
- Wrap content in `SwipeRefreshLayout`
- Set brand color for refresh indicator
- Remove manual "Refresh" button (or move to overflow)

**Validation:** Pull gesture triggers refresh, indicator shows

---

### 5.4 Add Log Filtering and Search

**Files:**
- `app/src/main/res/menu/menu_log_viewer.xml` (new)
- `app/src/main/java/com/camerainterceptor/ui/LogViewerActivity.java`

**Features:**
- SearchView for text search within logs
- Filter chips: ERROR, WARN, INFO, DEBUG (toggleable)
- Match highlighting in search results

**Validation:** Search and filters work correctly

---

### 5.5 Improve Actions with BottomAppBar

**Files:**
- `app/src/main/res/layout/activity_log_viewer.xml`
- `app/src/main/java/com/camerainterceptor/ui/LogViewerActivity.java`

**Layout:**
- `BottomAppBar` with navigation icon
- Actions: Share, Export, Copy All (in overflow)
- `FloatingActionButton` anchored to BottomAppBar for "Clear Logs"

**Clear Confirmation:**
- `MaterialAlertDialog` asking "Clear all logs?"
- Snackbar with "Undo" option after clearing

**Validation:** All actions work, confirmation dialog appears

---

### Phase 5 Completion Checklist

- [x] Layout converted to CoordinatorLayout with ConstraintLayout components
- [x] RecyclerView replaces ScrollView+TextView
- [x] Syntax highlighting by log level
- [x] Pull-to-refresh implemented
- [x] Search within logs
- [x] Filter by log level
- [x] BottomAppBar with actions
- [x] Clear confirmation with undo
- [x] Share/Export functionality
- [x] `gradle assembleDebug` passes
- [x] Changes committed: `feat(ui): Phase 5 - Log Viewer Enhancement`

---

## Phase 6: Responsive & Adaptive Layouts

**Status:** ⬜ Not Started  
**Estimated Files:** 8-12 new layout files  
**Dependencies:** Phases 2-5 complete

### 6.1 Create Landscape Layouts

**Files:**
- `app/src/main/res/layout-land/activity_settings.xml`
- `app/src/main/res/layout-land/fragment_image_picker.xml`
- `app/src/main/res/layout-land/activity_app_selection.xml`
- `app/src/main/res/layout-land/activity_log_viewer.xml`

**Adaptations:**
- Side-by-side arrangements where appropriate
- Adjusted preview sizes
- Optimal use of horizontal space

**Validation:** Rotate device, layouts adapt without overflow/cropping

---

### 6.2 Create Tablet Layouts

**Files:**
- `app/src/main/res/layout-sw600dp/` (7" tablets)
- `app/src/main/res/layout-sw720dp/` (10" tablets)

**Adaptations:**
- Master-detail pattern for App Selection
- Two-pane layout for Settings + detail
- Larger touch targets and spacing
- Max content width constraints (840dp)

**Validation:** Test on tablet emulator, layouts appropriate

---

### 6.3 Ensure ConstraintLayout Throughout

**Files:** All layout files

**Requirements:**
- No nested `LinearLayout` more than 2 levels deep
- Use `ConstraintLayout` chains and barriers
- Percentage-based constraints where appropriate
- `Guideline` for consistent margins

**Validation:** Layout inspector shows flat hierarchy

---

### 6.4 Handle Soft Keyboard

**File:** `AndroidManifest.xml`

**Per Activity:**
```xml
android:windowSoftInputMode="adjustResize|stateHidden"
```

**Implementation:**
- Content scrolls when keyboard appears
- FAB moves above keyboard
- No content hidden behind keyboard

**Validation:** Keyboard doesn't obscure input fields or buttons

---

### Phase 6 Completion Checklist

- [ ] Landscape layouts for all screens
- [ ] Tablet (sw600dp) layouts created
- [ ] Large tablet (sw720dp) layouts if needed
- [ ] ConstraintLayout used throughout
- [ ] Soft keyboard handling configured
- [ ] Tested on multiple screen sizes
- [ ] `gradle assembleDebug` passes
- [ ] Changes committed: `feat(ui): Phase 6 - Responsive Layouts`

---

## Phase 7: In-App Documentation & Onboarding

**Status:** ⬜ Not Started  
**Estimated Files:** 6-10 new files  
**Dependencies:** Phase 1 complete

### 7.1 Create Onboarding Flow

**Files:**
- `app/src/main/java/com/camerainterceptor/ui/OnboardingActivity.java` (new)
- `app/src/main/res/layout/activity_onboarding.xml` (new)
- `app/src/main/res/layout/fragment_onboarding_page.xml` (new)
- `app/src/main/java/com/camerainterceptor/ui/OnboardingAdapter.java` (new)

**Screens:**
1. **Welcome** — App introduction, purpose explanation
2. **Activation** — How to enable in LSPosed/Xposed
3. **Image Selection** — How to select replacement image
4. **App Targeting** — How to select which apps to intercept
5. **Get Started** — Button to enter main app

**Components:**
- `ViewPager2` with `FragmentStateAdapter`
- Page indicators (dots)
- Skip button
- Next/Done buttons

**Validation:** Onboarding displays, navigation works, persists completion state

---

### 7.2 Add First-Run Detection

**Files:**
- `app/src/main/java/com/camerainterceptor/ui/SettingsActivity.java`
- `app/src/main/java/com/camerainterceptor/utils/PreferenceManager.java`

**Implementation:**
- Check SharedPreferences for `onboarding_complete` key
- Launch OnboardingActivity if false/missing
- Set flag after onboarding completion

**Validation:** First launch shows onboarding, subsequent launches go to settings

---

### 7.3 Build Help/FAQ Screen

**Files:**
- `app/src/main/java/com/camerainterceptor/ui/HelpActivity.java` (new)
- `app/src/main/res/layout/activity_help.xml` (new)
- `app/src/main/res/layout/item_faq.xml` (new)
- `app/src/main/res/values/strings_help.xml` (new)

**Content:**
- Expandable FAQ items (using `MaterialCardView` or `ExpandableListView`)
- Common questions:
  - "How do I activate the module?"
  - "Why isn't it working in [app]?"
  - "How do I update the replacement image?"
  - "What modes are available?"
  - "How do I view logs?"
- Troubleshooting section
- Link to GitHub/external docs

**Validation:** Help screen accessible from settings, FAQ expands/collapses

---

### 7.4 Add Contextual Help Tooltips

**Files:**
- `app/src/main/res/xml/preferences.xml`
- `app/src/main/java/com/camerainterceptor/ui/SettingsFragment.java`

**Implementation:**
- Add `android:summary` to all preferences with helpful descriptions
- For complex settings, add info icon that shows `MaterialAlertDialog` with detailed explanation
- Use preference `dependency` to show/hide related settings

**Validation:** All settings have summaries, info dialogs work

---

### 7.5 Add Replay Onboarding Option

**Files:**
- `app/src/main/res/xml/preferences.xml`
- `app/src/main/java/com/camerainterceptor/ui/SettingsFragment.java`

**Implementation:**
- Add "View Tutorial" preference in About section
- Launches OnboardingActivity

**Validation:** Can replay onboarding from settings

---

### Phase 7 Completion Checklist

- [ ] OnboardingActivity with ViewPager2
- [ ] 4-5 onboarding pages with illustrations
- [ ] First-run detection logic
- [ ] HelpActivity with expandable FAQ
- [ ] Troubleshooting content
- [ ] Contextual summaries on all preferences
- [ ] Info dialogs for complex settings
- [ ] Replay tutorial option
- [ ] `gradle assembleDebug` passes
- [ ] Changes committed: `feat(ui): Phase 7 - Documentation & Onboarding`

---

## Phase 8: Polish & Micro-interactions

**Status:** ⬜ Not Started  
**Estimated Files:** Multiple modifications  
**Dependencies:** All previous phases complete

### 8.1 Add Activity Transitions

**Files:**
- `app/src/main/res/values/themes.xml`
- `app/src/main/res/anim/` (new directory with transition files)
- Activity Java files

**Transitions:**
- Fade through for most screen changes
- Container transform for list item → detail
- Shared element transitions where appropriate

**Validation:** Smooth transitions between screens

---

### 8.2 Implement Loading Indicators

**Files:** All Activity files

**Requirements:**
- `CircularProgressIndicator` for async operations
- Skeleton/shimmer for content loading
- Disabled state for buttons during operations
- Minimum display time (300ms) to prevent flicker

**Validation:** No jarring appearance/disappearance of content

---

### 8.3 Add Haptic Feedback

**Files:** Activity/Fragment files with interactive elements

**Implementation:**
- `HapticFeedbackConstants.CONFIRM` on successful actions
- `HapticFeedbackConstants.REJECT` on errors
- Subtle feedback on toggle switches

**Validation:** Tactile feedback felt on interactions (test on physical device)

---

### 8.4 Create Confirmation Dialogs

**Files:** Activities with destructive actions

**Dialogs Needed:**
- Clear logs confirmation
- Clear image selection confirmation
- Reset app selection confirmation

**Implementation:**
- `MaterialAlertDialog` with title, message, and actions
- Snackbar with "Undo" after destructive action where possible

**Validation:** Confirmation appears, undo works

---

### 8.5 Accessibility Audit

**Files:** All layout files and Activities

**Requirements:**
- `contentDescription` on all ImageViews and icon buttons
- Proper `labelFor` on form fields
- Minimum touch target size (48dp)
- Proper focus order (`nextFocusDown`, etc.)
- Color contrast validation (4.5:1 minimum)
- Screen reader announcements for state changes

**Testing:**
- Enable TalkBack and navigate entire app
- Use Accessibility Scanner tool

**Validation:** Full app navigable with TalkBack, no accessibility warnings

---

### Phase 8 Completion Checklist

- [ ] Activity transitions implemented
- [ ] Loading indicators on all async operations
- [ ] Haptic feedback on interactions
- [ ] Confirmation dialogs for destructive actions
- [ ] Undo functionality where appropriate
- [ ] All images have contentDescription
- [ ] Touch targets meet minimum size
- [ ] Color contrast verified
- [ ] TalkBack navigation tested
- [ ] `gradle assembleDebug` passes
- [ ] Changes committed: `feat(ui): Phase 8 - Polish & Micro-interactions`

---

## Appendix A: File Inventory

### Files to Create

| Path | Phase | Purpose |
|------|-------|---------|
| `res/values/colors.xml` | 1 | Color palette |
| `res/values/dimens.xml` | 1 | Dimension resources |
| `res/values/themes.xml` | 1 | Light theme |
| `res/values-night/themes.xml` | 1 | Dark theme |
| `res/values/styles.xml` | 1 | Component styles |
| `res/mipmap-*/ic_launcher.*` | 1 | App icons |
| `res/layout/activity_settings.xml` | 2 | Settings with toolbar |
| `res/drawable/ic_*.xml` | 2 | Preference icons |
| `java/.../ImagePickerFragment.java` | 3 | Bottom sheet picker |
| `res/layout/fragment_image_picker.xml` | 3 | Picker layout |
| `java/.../AppListAdapter.java` | 4 | RecyclerView adapter |
| `res/layout/item_app.xml` | 4 | App list item |
| `res/layout/layout_empty_state.xml` | 4 | Empty state |
| `java/.../LogEntryAdapter.java` | 5 | Log RecyclerView adapter |
| `res/layout/item_log_entry.xml` | 5 | Log entry item |
| `res/layout-land/*.xml` | 6 | Landscape layouts |
| `res/layout-sw600dp/*.xml` | 6 | Tablet layouts |
| `java/.../OnboardingActivity.java` | 7 | Onboarding flow |
| `java/.../HelpActivity.java` | 7 | Help/FAQ screen |
| `res/anim/*.xml` | 8 | Transition animations |

### Files to Modify

| Path | Phases | Changes |
|------|--------|---------|
| `app/build.gradle` | 1, 2 | Dependencies |
| `AndroidManifest.xml` | 6, 7 | New activities, soft input mode |
| `SettingsActivity.java` | 2, 7 | Migrate to AndroidX, first-run check |
| `ImagePickerActivity.java` | 3 | Deprecate/remove |
| `AppSelectionActivity.java` | 4 | RecyclerView, search, FAB |
| `LogViewerActivity.java` | 5 | RecyclerView, pull-refresh |
| `preferences.xml` | 2, 7 | Icons, summaries |
| `activity_app_selection.xml` | 4 | Complete redesign |
| `activity_log_viewer.xml` | 5 | Complete redesign |
| `app_list_item.xml` | 4 | Rename, redesign |
| `strings.xml` | All | New string resources |

---

## Appendix B: Resource Links

### Material Design 3

- [Material Design 3 Guidelines](https://m3.material.io/)
- [Material Components Android](https://github.com/material-components/material-components-android)
- [Material Theme Builder](https://m3.material.io/theme-builder)
- [Color System](https://m3.material.io/styles/color/overview)

### Android Documentation

- [AndroidX Preference](https://developer.android.com/develop/ui/views/components/settings)
- [RecyclerView](https://developer.android.com/develop/ui/views/layout/recyclerview)
- [ConstraintLayout](https://developer.android.com/develop/ui/views/layout/constraint-layout)
- [Supporting Different Screens](https://developer.android.com/guide/practices/screens_support)
- [Accessibility](https://developer.android.com/guide/topics/ui/accessibility)

### Tools

- [Android Asset Studio](https://romannurik.github.io/AndroidAssetStudio/)
- [Figma Material 3 Kit](https://www.figma.com/community/file/1035203688168086460)
- [Accessibility Scanner](https://play.google.com/store/apps/details?id=com.google.android.apps.accessibility.auditor)

---

## Revision History

| Date | Version | Changes | Author |
|------|---------|---------|--------|
| 2026-01-03 | 1.0 | Initial plan created | - |

---

*This document should be updated as implementation progresses. Mark phases complete, note any deviations, and keep the checklist current.*
