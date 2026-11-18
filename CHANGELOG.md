# Changelog

All notable changes to **IngeTrazo Mobile** are documented in this file and automatically published to the GitHub Releases page.

## [beta 0.1] - Initial Beta Release

### Added
- **Native 3D CAD Format Support**:
  - Full on-device parsing for **IngeTrazo (`.igz` v1, v2, v3)** including JSON geometry, binary `geom/*.bin` buffers, embedded textures, layers, saved views, dimensions, and section planes.
  - Clean-room **SketchUp (`.skp` VFF 2021+)** parser (`SkpParser.kt`), **Wavefront (`.obj`)**, and **glTF 2.0 (`.gltf` / `.glb`)** support via Android Storage Access Framework (SAF).
  - Bundled official IngeTrazo sample models (*Banca & Pérgola Yanque*, *Luminaria Solar Yanque*, *Arco Yanque*, *Pileta Fuente Yanque*, *Capas & Secciones*, *Pestañas & Escenas*).
- **Automatic Component-Based 3D Cut List (Bill of Materials) & PDF Export**:
  - Automatic extraction of 3D **Components** (`isComponent = true`) while excluding non-component group containers and 2D reference billboards.
  - Automatic decomposition of multi-part assembly groups into individual 3D solid components with rotation-invariant local oriented bounding-box calculation (`Length × Width × Height`) and quantity grouping.
  - **Bi-Directional 3D Selection & Camera Focus**: Tapping a Cut List row highlights all matching components in glowing orange and smoothly animates the 3D camera to fit them; tapping a component in 3D selects and scrolls to its Cut List row.
  - **Interactive 3D Exploded View (`0%` – `100%`)**: Real-time radial separation of individual components from the model center.
  - **Multi-Page A4 PDF Report Export**: Exports project header, live 3D viewport snapshot, and paginated Cut List table to `Downloads/` and Android's native Share Sheet.
- **3D CAD Viewport, Navigation & Adaptive Toolbars**:
  - OpenGL ES 3.0 Z-up turntable Orbit, Pan, Pinch Zoom, Perspective/Orthographic toggle, 7 Standard Views, Saved Scenes, and 5 Display Styles (*Shaded + Edges*, *Textured*, *Monochrome*, *X-Ray*, *Wireframe*).
  - Overflow scroll indicator arrows (`<` / `>`) on both top and bottom toolbars when screen width cannot fit all tools at once.
- **Precision Measurement & Angle Counter Suite**:
  - Magnetic Snap Engine (*Endpoint*, *Midpoint*, *On Edge*, *On Face*), 2-point Tape Measure, Protractor (*3-Point*, *2-Edge*, *2-Face Dihedral*), persistent Room storage, and CSV export.
- **ARCore Projection & Stereo VR Box Mode**:
  - Real-world metric scaling (`1:1` to `1:100`), Foundation / Below-Axis clipping toggle, Always-Level gravity lock, UV texture rendering, and stereoscopic side-by-side **VR Box Mode** with head tracking.
- **Project Hub & About Window**:
  - Streamlined Project Hub header with three-dot overflow menu for Dark/Light CAD theme toggle, Unit & Precision preferences (`m`, `cm`, `mm`, `ft`, `in`), and About dialog crediting **[Brillian11](https://github.com/Brillian11)** and the **[IngeTrazo Project](https://github.com/ingelibre/ingetrazo)**.
