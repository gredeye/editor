# VNX project format · schema 1

`.vnx` is a ZIP archive, MIME type `application/vnd.vynox.project`. It is not an MP4, app database dump, Java object stream, or executable script.

```text
MyProject.vnx
├── project.json
└── media/
    ├── <random UUID>.asset
    └── <random UUID>.asset
```

`ProjectJson` is the dedicated manifest codec. `VnxArchive` handles the container. Neither depends on UI components. `ProjectStore` atomically stores the same manifest in app-private storage; media lives in the adjacent `media` directory.

## Manifest

- `format`: `Vynox`
- `schema`: integer project format version (currently `1`); unknown versions are rejected with an explicit error.
- `appVersion`: producing application version, independent of schema.
- `id`, `name`, `metadata`: project identity and string metadata. Import assigns a **new local project ID** to prevent overwriting another local project; asset and layer IDs stay unchanged.
- `width`, `height`, `fps`, `durationUs`, `background`: composition configuration, integer microseconds and packed ARGB color.
- `assets`: asset objects with `id`, display `name`, MIME type, project-relative `file`, source duration and dimensions. A path is only a locator; layers reference the stable asset ID.
- `layers`: back-to-front ordered layer list. This list is the timeline and composition ordering, not a separate UI-only state.

Every layer stores identity/type, `parentId`, asset ID, blend, visibility/lock/mute, start/end/source-in times, text and shape attributes, numeric properties, effects and an optional mask. End time is exclusive. Layer-local animation time is `compositionTimeUs - startUs`; source media time is `sourceInUs + compositionTimeUs - startUs`.

### Animatable property

```json
{
  "value": 640.0,
  "keys": [
    {"timeUs": 0, "value": 100.0, "curve": [0.42, 0.0, 0.58, 1.0]},
    {"timeUs": 2000000, "value": 900.0, "curve": [0.0, 0.0, 1.0, 1.0]}
  ]
}
```

Without keys, use `value`. Outside the key range, hold the endpoint. Between keys, the **left key's** cubic Bezier curve maps normalized elapsed time to interpolation progress. Curve X control points are constrained to 0–1; Y may overshoot. Solve Bezier X numerically, then evaluate Y. Keys are unique by microsecond position; adding at the same time replaces a key. Moving onto another key is rejected.

Internal negative key times are intentional: a trim or split rebases the original key segment instead of approximating it with a newly eased endpoint. This preserves exact interpolation through the visible clip. Keyframes on effects and masks follow the same rebasing rules.

### Effects and masks

Effects contain `kind`, `enabled`, and `parameters` (a name → property map). Current filters use an animatable `amount`. Unknown effect names are preserved but produce an explicit renderer error until supported; the editor does not claim to apply them.

A mask contains `kind` (`RECTANGLE` or `ELLIPSE`), `invert`, and properties for local center offset, width, height, feather and opacity. It is applied to the effected layer before transforming/compositing that layer. Feather is a box-filter radius capped at 64 local pixels.

## Portability and missing media

Export includes each available referenced asset once. Files are copied, never resolved using another installation's absolute paths. A missing asset remains in the manifest; opening reconstructs editable layers and shows a relink flow. Relinking preserves its asset ID but writes a new immutable media file so existing undo snapshots keep their original bytes.

To share: save a `.vnx` through Android's document picker, then send that file using Files. On another installation use **Open .vnx**, or a file-provider MIME-aware Open With flow. Providers that label the extension as generic ZIP may not offer Open With; the app's import picker accepts it regardless.

## Security and limits

- Only `project.json` and `media/[A-Za-z0-9._-]+` file entries are accepted.
- Canonical path containment prevents escaping project storage. Duplicate entries and unknown archive files are rejected.
- 501 archive entries maximum, 4 GB total uncompressed bytes, 8 MB JSON manifest, 500 assets, 200 layers, 10,000 keys per property.
- Canvas dimensions 16–3840; FPS 1–60; duration up to one hour. MP4 export additionally requires even dimensions.
- Parent cycles, missing parents, duplicate IDs, invalid clip ranges and unsupported schema versions are rejected.
- ZIP import goes to a new app-private directory. Failed imports remove that directory. No shell, script, network, plugin binary or arbitrary URI is executed by parsing.

This is a v1 format, not a promise of arbitrary future-version compatibility. Future schema changes should introduce an explicit migration step, retain tests for previous fixtures and never silently discard editing data.
