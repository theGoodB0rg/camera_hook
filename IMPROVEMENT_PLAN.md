# Camera Interceptor - Robustness Improvement Plan

**Created:** January 3, 2026  
**Goal:** Make the camera interceptor reliable for ~80-90% of real-world camera apps

---

## 📋 Problem Context

### What This App Does
Camera Interceptor is an Xposed module that intercepts camera capture operations and replaces the captured image with a pre-selected image. This allows users to "inject" any image when an app takes a photo.

### Current State (Working Baseline)
- ✅ **Open Camera** - Successfully intercepts and injects images
- ✅ Legacy Camera API (`Camera.takePicture()`) - Working
- ✅ `Bitmap.compress()` hook - Working
- ✅ `FileOutputStream` constructor hooks - Working
- ✅ `ContentResolver.openOutputStream()` wrapping - Working
- ✅ Image saved to world-readable location (`/sdcard/.camerainterceptor/`)
- ✅ JPEG conversion from any input format
- ✅ Recursion prevention with `isLoadingImage` ThreadLocal flag

### Current Issues (Not Working)
| App | Issue | Root Cause |
|-----|-------|------------|
| **Google Camera** | No interception | Uses native code (NDK), bypasses Java APIs entirely |
| **PixelLab** | Was crashing | Tried to hook abstract `OutputStream.write()` - fixed by removal |
| **Some modern apps** | No interception | Use MediaStore API paths we don't fully cover |

### Technical Constraints
1. **Cannot hook abstract methods** - Xposed limitation (e.g., `OutputStream.write()`, `CameraCaptureSession.capture()`)
2. **Cannot hook native code** - JNI/NDK operations are outside Xposed's reach
3. **Cannot hook system processes** - Only works within app context
4. **XSharedPreferences limitations** - Target apps can't read our app's private storage

---

## 🔬 Research Findings: How Camera Apps Save Images

### The 3 Main Save Paths (All Hookable)

```
┌─────────────────────────────────────────────────────────────────┐
│                    IMAGE SAVE FLOW DIAGRAM                      │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Camera Capture                                                 │
│       │                                                         │
│       ▼                                                         │
│  ┌─────────┐                                                    │
│  │ Bitmap  │                                                    │
│  └────┬────┘                                                    │
│       │                                                         │
│       ├──────────────┬──────────────┬──────────────┐           │
│       ▼              ▼              ▼              ▼           │
│  ┌─────────┐   ┌──────────┐   ┌──────────┐   ┌──────────┐     │
│  │ Path 1  │   │  Path 2  │   │  Path 3  │   │  Path 4  │     │
│  │Bitmap.  │   │ByteArray │   │Content   │   │MediaStore│     │
│  │compress │   │OutputStream│ │Resolver  │   │.insert   │     │
│  │   +     │   │    +     │   │.openOut  │   │ Image()  │     │
│  │  FOS    │   │FOS.write │   │putStream │   │          │     │
│  └────┬────┘   └────┬─────┘   └────┬─────┘   └────┬─────┘     │
│       │             │              │              │            │
│       ▼             ▼              ▼              ▼            │
│  ┌─────────────────────────────────────────────────────────┐  │
│  │                    SAVED IMAGE FILE                      │  │
│  └─────────────────────────────────────────────────────────┘  │
│                                                                 │
│  ✅ = Currently Hooked    ⚠️ = Partially Hooked    ❌ = Missing │
│                                                                 │
│  Path 1: ✅ Bitmap.compress + ✅ FileOutputStream              │
│  Path 2: ⚠️ FOS.write (only some overloads)                    │
│  Path 3: ✅ ContentResolver.openOutputStream (wrapped)         │
│  Path 4: ❌ MediaStore.Images.Media.insertImage                │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### Common Code Patterns in Real Apps

**Pattern 1: Direct FileOutputStream + Bitmap.compress** (VERY COMMON - ~60% of apps)
```java
FileOutputStream out = new FileOutputStream(file);
bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out);
out.close();
```
**Status:** ✅ HOOKED via Bitmap.compress

**Pattern 2: ByteArrayOutputStream → FileOutputStream.write()** (COMMON - ~20% of apps)
```java
ByteArrayOutputStream bytes = new ByteArrayOutputStream();
bitmap.compress(Bitmap.CompressFormat.JPEG, 60, bytes);
FileOutputStream fo = new FileOutputStream(file);
fo.write(bytes.toByteArray());  // Direct byte array write
fo.close();
```
**Status:** ⚠️ PARTIALLY HOOKED - Need to improve FileOutputStream.write hooks

**Pattern 3: ContentResolver + MediaStore** (MODERN - ~15% of apps, growing)
```java
Uri uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
OutputStream imageOut = contentResolver.openOutputStream(uri);
bitmap.compress(Bitmap.CompressFormat.JPEG, 50, imageOut);
imageOut.close();
```
**Status:** ✅ HOOKED via InterceptingOutputStream wrapper

**Pattern 4: MediaStore.Images.Media.insertImage()** (LEGACY but still used - ~5% of apps)
```java
MediaStore.Images.Media.insertImage(getContentResolver(), bitmap, title, description);
```
**Status:** ❌ NOT HOOKED - Easy to add

---

## 📊 Current Hook Coverage Matrix

| Hook Target | Class | Method | Status | Notes |
|-------------|-------|--------|--------|-------|
| Legacy Camera | `Camera` | `takePicture()` | ✅ Working | Intercepts callback |
| Camera2 ImageReader | `ImageReader` | `acquireLatestImage()` | ✅ Working | Returns modified Image |
| Camera2 ImageReader | `ImageReader` | `acquireNextImage()` | ✅ Working | Returns modified Image |
| Bitmap Save | `Bitmap` | `compress()` | ✅ Working | Replaces bitmap before compress |
| File Creation | `FileOutputStream` | `<init>(File)` | ✅ Working | Tracks JPEG files |
| File Creation | `FileOutputStream` | `<init>(String)` | ✅ Working | Tracks JPEG files |
| File Write | `FileOutputStream` | `write(byte[])` | ⚠️ Partial | Needs refinement |
| MediaStore | `ContentResolver` | `openOutputStream()` | ✅ Working | Wrapped with InterceptingOutputStream |
| MediaStore Direct | `MediaStore.Images.Media` | `insertImage()` | ❌ Missing | Should add |
| Scoped Storage | `ContentResolver` | `openFileDescriptor()` | ❌ Missing | For modern apps |
| File Provider | `FileProvider` | `getUriForFile()` | ❌ Missing | For sharing flows |

---

## 🚀 Phase-by-Phase Improvement Plan

### Phase 1: Stabilization & Bug Fixes
**Goal:** Ensure current hooks are rock-solid  
**Estimated Coverage:** 65% → 70%  
**Effort:** Low

#### Tasks:
1. **[ ] Fix FileOutputStream.write() hooks**
   - Hook `write(byte[], int, int)` overload (concrete method)
   - Add JPEG magic byte detection before replacement
   - Ensure no double-replacement issues

2. **[ ] Improve JPEG detection**
   - Add more robust JPEG header validation
   - Handle partial writes gracefully
   - Add EXIF preservation option

3. **[ ] Add comprehensive logging**
   - Log which hook was triggered
   - Log file paths and sizes
   - Add debug mode toggle in settings

4. **[ ] Handle edge cases**
   - Multiple rapid captures
   - Very large images
   - Memory pressure situations

#### Files to Modify:
- `FileOutputHook.java` - Improve write() hooks
- `HookDispatcher.java` - Add logging utilities
- `CameraHook.java` - Add logging

---

### Phase 2: MediaStore API Coverage
**Goal:** Cover modern Android 10+ save paths  
**Estimated Coverage:** 70% → 80%  
**Effort:** Medium

#### Tasks:
1. **[ ] Hook `MediaStore.Images.Media.insertImage()`**
   - All 4 overloads
   - Replace bitmap parameter before insertion
   - Handle both Bitmap and String (path) variants

2. **[ ] Hook `ContentResolver.insert()` for Images**
   - Detect when inserting to `MediaStore.Images.Media.EXTERNAL_CONTENT_URI`
   - Track the returned URI for subsequent write interception
   - Link with openOutputStream hook

3. **[ ] Hook `ContentResolver.openFileDescriptor()`**
   - Modern scoped storage uses this
   - Wrap returned ParcelFileDescriptor
   - Intercept writes through FileDescriptor

4. **[ ] Add `MediaScannerConnection` hook**
   - Some apps scan files after saving
   - Ensure our injected image is what gets scanned

#### New Files to Create:
- `MediaStoreHook.java` - All MediaStore-related hooks

#### Files to Modify:
- `FileOutputHook.java` - Add ParcelFileDescriptor handling
- `HookDispatcher.java` - Register new hooks

---

### Phase 3: Alternative Save Path Coverage
**Goal:** Cover less common but valid save paths  
**Estimated Coverage:** 80% → 85%  
**Effort:** Medium

#### Tasks:
1. **[ ] Hook `BitmapFactory.decodeByteArray()` output usage**
   - Some apps decode → modify → save
   - Track decoded bitmaps for replacement

2. **[ ] Hook `Canvas.drawBitmap()` for screenshots**
   - Apps using canvas-based image composition
   - Replace source bitmap before drawing

3. **[ ] Hook `ImageWriter` class (Camera2)**
   - Used for reprocessing capture requests
   - Intercept image queue operations

4. **[ ] Hook common image libraries**
   - `Glide` save operations
   - `Picasso` save operations
   - `Coil` save operations (if detectable)

#### New Files to Create:
- `ImageLibraryHooks.java` - Third-party library hooks

#### Files to Modify:
- `Camera2Hook.java` - Add ImageWriter hooks

---

### Phase 4: Reliability & Edge Cases
**Goal:** Handle real-world edge cases  
**Estimated Coverage:** 85% → 88%  
**Effort:** Medium-High

#### Tasks:
1. **[ ] Add app-specific quirk handling**
   - Configuration file for known app behaviors
   - Per-app hook enable/disable
   - Custom hook timing adjustments

2. **[ ] Implement hook priority system**
   - Ensure our hooks run at the right time
   - Handle hook conflicts with other Xposed modules

3. **[ ] Add retry mechanism**
   - If first interception fails, try alternative hooks
   - Fallback chain: Bitmap.compress → FOS.write → MediaStore

4. **[ ] Memory optimization**
   - Lazy load injected image
   - Clear cache after successful injection
   - Handle OOM gracefully

5. **[ ] Add verification system**
   - After save, verify the saved file contains injected image
   - Log success/failure statistics

#### New Files to Create:
- `AppQuirks.java` - App-specific configurations
- `HookPriorityManager.java` - Hook ordering system

#### Files to Modify:
- `HookDispatcher.java` - Add priority and retry logic

---

### Phase 5: User Experience & Polish
**Goal:** Make the app user-friendly and maintainable  
**Estimated Coverage:** 88% → 90%  
**Effort:** Low-Medium

#### Tasks:
1. **[ ] Add success/failure notifications**
   - Toast or notification when image is injected
   - Show which app triggered the injection

2. **[ ] Add injection history**
   - Log of all successful injections
   - Timestamp, app name, file path

3. **[ ] Add per-app whitelist/blacklist**
   - Choose which apps to intercept
   - Exclude system apps by default

4. **[ ] Add multiple injection images**
   - Gallery of pre-loaded images
   - Quick switch between images

5. **[ ] Add image preview before injection**
   - Show what will be injected
   - Confirm before saving to storage

6. **[ ] Add automatic updates check**
   - Notify when new version available
   - Show changelog

#### Files to Modify:
- `ImagePickerActivity.java` - Enhanced UI
- `MainActivity.java` - Settings and history
- Create new activities for history/settings

---

## 📈 Expected Coverage by Phase

```
Phase 0 (Current):  ████████████░░░░░░░░ 65%
Phase 1 (Stable):   ██████████████░░░░░░ 70%
Phase 2 (MediaStore):████████████████░░░░ 80%
Phase 3 (Alt Paths): █████████████████░░░ 85%
Phase 4 (Reliability):█████████████████▓░░ 88%
Phase 5 (Polish):    ██████████████████░░ 90%
```

---

## ⚠️ Known Limitations (Cannot Fix)

These are fundamental limitations that cannot be overcome:

1. **Google Camera & Pixel Camera**
   - Uses native code (C/C++) for image processing
   - Bypasses Java APIs entirely
   - **Workaround:** None - recommend users use alternative camera apps

2. **Banking/Secure Apps**
   - Anti-tampering detection may block Xposed
   - May crash or refuse to run
   - **Workaround:** Add to blacklist

3. **System Camera Service**
   - Some captures happen in system_server
   - Outside app process scope
   - **Workaround:** Hook at app level only

4. **Hardware-level Captures**
   - Screenshot buttons, etc.
   - Not routed through Camera APIs
   - **Workaround:** None

---

## 🔧 Technical Implementation Notes

### Safe Hook Patterns
```java
// ✅ SAFE: Hook concrete class methods
XposedHelpers.findAndHookMethod(FileOutputStream.class, "write", 
    byte[].class, int.class, int.class, new XC_MethodHook() {...});

// ✅ SAFE: Hook final methods
XposedHelpers.findAndHookMethod(Bitmap.class, "compress",
    Bitmap.CompressFormat.class, int.class, OutputStream.class, ...);

// ❌ UNSAFE: Cannot hook abstract methods
XposedHelpers.findAndHookMethod(OutputStream.class, "write", byte[].class, ...);
// This will throw "Cannot hook abstract methods"

// ❌ UNSAFE: Cannot hook interface methods directly
XposedHelpers.findAndHookMethod(Closeable.class, "close", ...);
```

### JPEG Detection
```java
private boolean isJpegData(byte[] data) {
    if (data == null || data.length < 3) return false;
    // JPEG magic bytes: FF D8 FF
    return (data[0] & 0xFF) == 0xFF && 
           (data[1] & 0xFF) == 0xD8 && 
           (data[2] & 0xFF) == 0xFF;
}
```

### Thread Safety
```java
// Use ThreadLocal to prevent recursion
private static final ThreadLocal<Boolean> isInjecting = 
    ThreadLocal.withInitial(() -> false);

// In hook:
if (isInjecting.get()) return; // Prevent infinite loop
isInjecting.set(true);
try {
    // Do injection
} finally {
    isInjecting.set(false);
}
```

---

## 📁 Project Structure After All Phases

```
app/src/main/java/com/camerainterceptor/
├── hooks/
│   ├── HookDispatcher.java      # Main hook coordinator
│   ├── CameraHook.java          # Legacy Camera API
│   ├── Camera2Hook.java         # Camera2 API
│   ├── FileOutputHook.java      # File/Stream operations
│   ├── MediaStoreHook.java      # NEW: MediaStore operations
│   ├── ImageLibraryHooks.java   # NEW: Third-party libraries
│   └── AppQuirks.java           # NEW: Per-app configurations
├── utils/
│   ├── ImageUtils.java          # Image manipulation
│   ├── JpegUtils.java           # JPEG detection/validation
│   ├── LogUtils.java            # NEW: Logging utilities
│   └── HookPriorityManager.java # NEW: Hook ordering
├── ui/
│   ├── MainActivity.java        # Main settings
│   ├── ImagePickerActivity.java # Image selection
│   ├── HistoryActivity.java     # NEW: Injection history
│   └── AppListActivity.java     # NEW: Whitelist/blacklist
└── interfaces/
    └── ... (existing)
```

---

## 🎯 Success Criteria

The improvement plan is considered successful when:

1. **Coverage:** 80%+ of popular camera apps work correctly
2. **Stability:** No crashes in hooked apps
3. **Performance:** < 100ms overhead per capture
4. **Reliability:** 95%+ success rate for supported apps
5. **Usability:** Non-technical users can set it up easily

---

## 📝 Testing Checklist

### Must-Test Apps (Phase 1-2)
- [ ] Open Camera (baseline - must keep working)
- [ ] Simple Camera
- [ ] Camera MX
- [ ] Snap Camera HDR
- [ ] Instagram (camera feature)
- [ ] WhatsApp (camera feature)
- [ ] Telegram (camera feature)

### Should-Test Apps (Phase 3-4)
- [ ] Facebook (camera feature)
- [ ] Snapchat (may have anti-tamper)
- [ ] TikTok (may have anti-tamper)
- [ ] Twitter/X (camera feature)
- [ ] Document scanners (CamScanner, etc.)

### Known Non-Working (Verify Graceful Failure)
- [ ] Google Camera - should fail silently
- [ ] Pixel Camera - should fail silently
- [ ] Banking apps - should be blacklisted

---

## 🔄 Next Steps

1. **Immediate:** Start Phase 1 - Fix FileOutputStream.write() hooks
2. **This Week:** Complete Phase 1, begin Phase 2
3. **Next Week:** Complete Phase 2, test with modern apps
4. **Ongoing:** Gather user feedback, add app-specific quirks

---

*This document will be updated as implementation progresses.*
