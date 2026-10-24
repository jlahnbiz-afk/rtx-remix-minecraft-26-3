# PORTING.md: Minecraft 26.3 (Fabric) 移植メモ

このフォークで Minecraft 1.21.1 から 26.3 へ移植する作業の進捗メモです。
`CLAUDE.md` と `AGENTS.md` は上流 (NVIDIA) のファイルなので変更せず、移植に
関する記録はすべてこのファイルに書きます。上流の `AGENTS.md` は計画やチェック
リストをリポジトリに置かないよう求めていますが、このファイルはフォークの判断で
置いているものです。上流へ PR を送るときはこのファイルを含めないでください。

記号: ✅ 完了 / 🚧 作業中 / ⛔ ブロック中 / ⬜ 未着手。
「確度」の欄は、外部資料で確認できていない知識に基づく記述を区別するためのもの
です (高 / 中 / 低)。確度が「高」以外の項目は、26.3 の jar とソースを手元で
確認してから作業してください。

## 現状 (2026-10-24)

⛔ **ビルド環境がブロック中。** 作業用クラウド環境のネットワークポリシーで、
以下のホストへの接続が 403 で拒否されています。Minecraft 本体、Fabric Loader、
Loom、Fabric API のどれも取得できないため、コンパイル確認ができません。

- `maven.fabricmc.net` (Loom、Loader、Fabric API、マッピング)
- `meta.fabricmc.net` (バージョン一覧)
- `piston-meta.mojang.com`、`launchermeta.mojang.com` (バージョンマニフェスト)
- `piston-data.mojang.com`、`libraries.minecraft.net` (クライアント jar、ライブラリ)

コンパイルできない状態で 76,000 行のコードを推測で書き換えると、壊れたコードが
積み上がるだけなので、ソースコードにはまだ手を入れていません。

もう一つの前提として、このビルドは Windows と MSVC が必要です (`classes` が
`setupRuntime` に依存し、JNI ブリッジをコンパイルするため)。Linux 環境では
最大でも Java のコンパイルと JVM テストまでしか確認できず、描画の確認は
`tools\rtx.bat launch` を使う Windows 実機でしか行えません。

## 対象バージョン

| 項目 | 移植前 | 移植後 | 状態 |
| --- | --- | --- | --- |
| Minecraft | 1.21.1 | 26.3 | ⬜ バージョン番号を Fabric Meta で確認 |
| マッピング | Yarn `1.21.1+build.3` | なし (26.1 以降は難読化なし、Mojang 名) | ⬜ 確度: 高 |
| Java | 21 | 25 | ⬜ 確度: 高 (26.1 から Java 25 必須) |
| Fabric Loader | 0.16.5 | 未定 | ⬜ |
| Fabric API | 0.116.10+1.21.1 | 未定 (`+26.3`) | ⬜ |
| Loom | `fabric-loom` 1.15.5 | 未定 | ⬜ 難読化なし版向けのプラグイン ID と設定を確認 |
| Gradle | 9.3.1 | 未定 | ⬜ Loom の要求に合わせる |

確認できたらこの表を埋めて、下の「ツールチェーン」に作業を移してください。

## 規模

- Java ソース: 387 ファイル、約 76,000 行 (`rtxremix-java/` を含む)
- `net.minecraft` を import するファイル: main 162、test 37
- Mixin: 72 クラス (`src/main/resources/rtx-remix-renderer.mixins.json`)
- Access widener: 88 行 (`src/main/resources/rtx-remix-renderer.accesswidener`)
- Fabric API の利用: rendering v1、renderer v1 (Indigo の API)、fluid render、
  lifecycle events、client command v2、keybinding、networking、screen、
  resource loader、particle、item group、screen handler
- `rtxremix-java/` は Minecraft に依存しないため、移植の影響はありません。
  ネイティブランタイム (dxvk-remix) も同様です。

1.21.1 から 26.3 までの間に、Minecraft の描画系は複数回作り直されています。
このモッドは vanilla の描画パスの中に入り込んで、地形、エンティティ、パーティクル、
テクスチャをその場で取り出す設計なので、名前の置き換えだけでは済まず、主要な
キャプチャ経路の多くを設計し直す必要があります。

## 進め方

一度に 26.3 へ飛ぶと、コンパイルエラーが数千件出て、どれが名前の問題でどれが
設計の問題なのか区別できなくなります。コンパイルが通る状態を保ちながら、段階を
分けて進めます。

### 段階 1: 1.21.1 のまま Yarn から Mojang 名へ移行 ⬜

26.1 以降は難読化がなくなり、Fabric も Mojang の名前を使います。1.21.1 の
まま名前だけを先に移行すれば、動作を変えずにコンパイルとテストで検証できます。

1. ネットワークのブロックを解除する。
2. `build.gradle` で `./gradlew migrateMappings --mappings "net.minecraft:mappings:1.21.1"`
   を実行して、`src/main/java` と `src/test/java` を書き換える (Loom の機能)。
3. `mappings loom.officialMojangMappings()` に切り替える。
4. Access widener は `migrateMappings` の対象外なので、88 行を手で Mojang 名に
   書き換える。Mojang マッピングでも Loom の named 名前空間を使うので、ヘッダ
   `accessWidener v2 named` はそのまま。26.1 以降の名前空間とファイル形式は
   段階 2 で確認する。
5. Mixin の `targets = "..."` の文字列指定 (`RenderedChunkMixin`、
   `ClientChunkMixin`、`ClientChunkSnapshotAccess`、`RebuildTaskMixin`) と、
   `@At(target = "L...;")` の記述子は自動で書き換わらないので手で直す。
6. Windows で `.\gradlew.bat build test` と `.\tools\rtx.bat launch` を確認する。

### 段階 2: ツールチェーンを 26.3 に上げる ⬜

- `build.gradle`: Loom のプラグイン ID とバージョン、`minecraft` の依存、
  `mappings` 行の削除 (26.1 以降)、`modImplementation` から `implementation`
  への変更 (再マップ不要のため、確度: 中)、Java toolchain と `options.release`
  を 25 に、`fabricApiCoordinate`。
- `fabric.mod.json`: `depends` の `minecraft`、`fabricloader`、`fabric-api`、`java`。
- `rtx-remix-renderer.mixins.json`: `compatibilityLevel` を Java 25 に対応する値へ。
- `loom.runs.client.vmArgs` と `packaging/installer.ps1` の
  `RECOMMENDED_JVM_ARGS`: Launcher の 26.3 既定値に合わせ、旧値を
  `PREVIOUS_RECOMMENDED_JVM_ARGS` に追記する (`AGENTS.md` の規則)。
- `packaging/installer.ps1`: `FABRIC_API_VERSION`、Minecraft と Java のバージョン。
- `tools/src/rtx_tools/` (`prism.py`、`prism_launcher.py`、`install.py`、
  `client.py`、`benchmark/runner.py`) とそのテスト: Prism インスタンスの
  Minecraft と Java のバージョン。
- ドキュメント: `README.md`、`docs/`、`packaging/INSTALL.md`、`tools/docs/`
  に残る `1.21.1` と `Java 21`。

### 段階 3: 描画系を作り直す ⬜

下の Mixin 一覧の「リスク」が高いものから順に、領域単位で進めます。各領域は
「vanilla 側で何が変わったかを 26.3 のソースで確認する」「キャプチャの差し込み
位置を決める」「コンパイルを通す」「Windows 実機で見た目を確認する」の順です。

### 段階 4: 検証 ⬜

- `.\gradlew.bat build test`、`.\tools\rtx.bat check --tests`
- `.\tools\rtx.bat launch` で地形、エンティティ、パーティクル、空、雲、
  ライト、オーバーレイ入力を目視確認
- `AGENTS.md` の Invariants の各項目 (特に可視性ウォークの一致確認
  `-Drtxremix.vanillaVisibilityWalk`、`-Drtxremix.logVisibleSet`)
- ベンチマーク (`tools/docs/BENCHMARKING.md`) で 1.21.1 版と比較

## 1.21.1 以降の vanilla の主な変更 (要確認)

社内資料ではなく公開されている変更履歴の記憶に基づくまとめです。26.3 の
ソースで一つずつ確認してから作業してください。

| 時期 | 変更 | 影響する領域 | 確度 |
| --- | --- | --- | --- |
| 1.21.2 | エンティティ描画が `EntityRenderState` 経由になる | エンティティ、名札、プレイヤー | 高 |
| 1.21.2 | `RenderType` とシェーダの指定方法が変わる | エンティティの材質判定 (`RenderLayerUtils`) | 中 |
| 1.21.4 | アイテムモデルの再設計 (`ItemStackRenderState`、items/*.json) | 手持ち、アイテム描画 | 高 |
| 1.21.4 | `HttpTexture` が廃止されスキンのダウンロードが別クラスへ | `PlayerSkinTextureMixin` | 中 |
| 1.21.5 | Blaze3D の `GpuDevice`、`RenderPipeline`、`GpuTexture`、`GpuBuffer` 導入。`VertexBuffer` 廃止、`RenderSystem` 縮小 | GL 状態、テクスチャ転送、地形バッファ | 高 |
| 1.21.5 | `BakedModel` が `BlockStateModel` と `BlockModelPart` に分割。`WeightedBakedModel` と `MultiPartBakedModel` も置き換え | ブロックモデル選択の割り当てなし移植 | 高 |
| 1.21.6 | GUI 描画が render state 化。地形セクション描画の整理 | 画面、地形 | 中 |
| 1.21.9 | エンティティ、ブロックエンティティ、ModelPart の描画が「提出」方式 (`SubmitNodeCollector`) になる | エンティティキャプチャ全体 | 中 |
| 1.21.9 | パーティクル描画の再構成 (グループ単位、`TextureSheetParticle` の扱い) | パーティクル | 中 |
| 1.21.9 | ワールド読み込み画面の統合 | `DownloadingTerrainScreenMixin`、`JoinBurstHold` | 低 |
| 26.1 | 難読化の廃止。Java 25 必須 | ビルド全体 | 高 |
| 26.1〜26.3 | 未調査 | すべて | 未確認 |

描画バックエンド (OpenGL のままか、別の API へ移ったか) は 26.3 の時点で確認
が必要です。このモッドは GLFW ウィンドウに Remix ランタイムを載せているので、
バックエンドが変わっていると `RemixRenderer`、`WindowMixin`、`gl/` の初期化
方法が変わります。

## Mixin 一覧

「Yarn 1.21.1」は現在のコードの名前、「Mojang 1.21.1」は段階 1 で使う名前です。
Mojang 名は記憶に基づくので、`migrateMappings` の結果で上書きしてください。
「リスク」は 26.3 で対象そのものが消えているか、差し込み位置の意味が変わって
いる可能性の目安です。

### 地形 (`terrain/` 約 10,500 行、最大の領域)

| Mixin | Yarn 1.21.1 | Mojang 1.21.1 | リスク | 状態 |
| --- | --- | --- | --- | --- |
| `WorldRendererMixin` (920 行) | `WorldRenderer` | `LevelRenderer` | 高 | ⬜ |
| `ChunkRenderingDataPreparerMixin` (689 行) | `ChunkRenderingDataPreparer` | `SectionOcclusionGraph` | 高 | ⬜ |
| `RenderableChunksMixin` | `ChunkRenderingDataPreparer$RenderableChunks` | `SectionOcclusionGraph` の内部クラス | 高 | ⬜ |
| `SectionBuilderMixin` | `SectionBuilder` | `SectionCompiler` | 高 | ⬜ |
| `BuiltChunkMixin`、`BuiltChunkBuffersMixin` | `ChunkBuilder$BuiltChunk` | `SectionRenderDispatcher$RenderSection` | 高 | ⬜ |
| `RebuildTaskMixin` | `ChunkBuilder$BuiltChunk$RebuildTask` | `SectionRenderDispatcher$RenderSection$RebuildTask` | 高 | ⬜ |
| `BuiltChunkStorageMixin` | `BuiltChunkStorage` | `ViewArea` | 中 | ⬜ |
| `BlockBufferBuilderPoolMixin` | `BlockBufferBuilderPool` | `SectionBufferBuilderPool` | 中 | ⬜ |
| `VertexBufferMixin` | `VertexBuffer` | `VertexBuffer` (1.21.5 で廃止) | 高 | ⬜ |
| `ChunkRendererRegionMixin` | `ChunkRendererRegion` | `RenderChunkRegion` | 中 | ⬜ |
| `ChunkRendererRegionBuilderMixin` | `ChunkRendererRegionBuilder` | `RenderRegionCache` | 中 | ⬜ |
| `ClientChunkMixin`、`ClientChunkSnapshotAccess` | `ChunkRendererRegionBuilder$ClientChunk` | `RenderRegionCache` の内部クラス | 中 | ⬜ |
| `RenderedChunkMixin` | `RenderedChunk` | `RenderChunk` | 中 | ⬜ |
| `FrustumAccessor` | `Frustum` | `Frustum` | 低 | ⬜ |
| `ClientChunkManagerMixin` | `ClientChunkManager` | `ClientChunkCache` | 中 | ⬜ |
| `WorldChunkMixin` | `WorldChunk` | `LevelChunk` | 低 | ⬜ |
| `PackedIntegerArrayMixin` | `PackedIntegerArray` | `SimpleBitStorage` | 低 | ⬜ |
| `BakedQuadMixin` | `BakedQuad` | `BakedQuad` (1.21.5 以降 record 化の可能性) | 中 | ⬜ |
| `WeightedBakedModelMixin` | `WeightedBakedModel` | `WeightedBakedModel` (1.21.5 で置き換え) | 高 | ⬜ |
| `MultipartBakedModelMixin` | `MultipartBakedModel` | `MultiPartBakedModel` (1.21.5 で置き換え) | 高 | ⬜ |
| `BiomeMixin`、`ClientWorldColorMixin` | `Biome`、`ClientWorld` | `Biome`、`ClientLevel` | 低 | ⬜ |
| `FoliageColorsMixin`、`GrassColorsMixin` | `FoliageColors`、`GrassColors` | `FoliageColor`、`GrassColor` | 低 | ⬜ |
| `WallBlockMixin` | `WallBlock` | `WallBlock` | 低 | ⬜ |

### 光とチャンク配送 (統合サーバーを含む)

| Mixin | Yarn 1.21.1 | Mojang 1.21.1 | リスク | 状態 |
| --- | --- | --- | --- | --- |
| `LightStorageMixin` | `LightStorage` | `LayerLightSectionStorage` | 中 | ⬜ |
| `ChunkLightProviderAccessor` | `ChunkLightProvider` | `LightEngine` | 低 | ⬜ |
| `ChunkSkyLightMixin` | `ChunkSkyLight` | `ChunkSkyLightSources` | 低 | ⬜ |
| `ChunkDataSenderMixin` | `ChunkDataSender` | `PlayerChunkSender` | 中 | ⬜ |
| `ChunkHolderMixin` | `ChunkHolder` | `ChunkHolder` | 中 | ⬜ |
| `ServerChunkManagerMixin` | `ServerChunkManager` | `ServerChunkCache` | 中 | ⬜ |
| `ThreadExecutorMixin` | `ThreadExecutor` | `BlockableEventLoop` | 低 | ⬜ |

### エンティティとアイテム (`entity/` 約 8,200 行)

| Mixin | Yarn 1.21.1 | Mojang 1.21.1 | リスク | 状態 |
| --- | --- | --- | --- | --- |
| `EntityRenderDispatcherMixin`、`EntityRenderDispatcherAccessor` | `EntityRenderDispatcher` | `EntityRenderDispatcher` | 高 | ⬜ |
| `BlockEntityRenderDispatcherMixin` | `BlockEntityRenderDispatcher` | `BlockEntityRenderDispatcher` | 高 | ⬜ |
| `ModelPartMixin` | `ModelPart` | `ModelPart` | 高 | ⬜ |
| `CuboidMixin` | `ModelPart$Cuboid` | `ModelPart$Cube` | 中 | ⬜ |
| `LivingEntityRendererNameMixin` | `LivingEntityRenderer` | `LivingEntityRenderer` | 高 | ⬜ |
| `PlayerEntityNameMixin` | `PlayerEntity` | `Player` | 中 | ⬜ |
| `Deadmau5FeatureRendererMixin` | `Deadmau5FeatureRenderer` | `Deadmau5EarsLayer` | 中 | ⬜ |
| `HeldItemRendererMixin` | `HeldItemRenderer` | `ItemInHandRenderer` | 高 | ⬜ |
| `ItemRendererMixin` | `ItemRenderer` | `ItemRenderer` | 高 | ⬜ |
| `MatrixStackAccessor`、`MatrixStackEntryAccessor` | `MatrixStack`、`MatrixStack$Entry` | `PoseStack`、`PoseStack$Pose` | 低 | ⬜ |
| `TorchBlockAccessor` | `TorchBlock` | `TorchBlock` | 中 | ⬜ |

### パーティクル (`particle/`)

| Mixin | Yarn 1.21.1 | Mojang 1.21.1 | リスク | 状態 |
| --- | --- | --- | --- | --- |
| `ParticleManagerMixin`、`ParticleManagerAccessor` | `ParticleManager` | `ParticleEngine` | 高 | ⬜ |
| `ParticleAccessor`、`ParticleStateMixin` | `Particle` | `Particle` | 中 | ⬜ |
| `BillboardParticleAccessor` | `BillboardParticle` | `SingleQuadParticle` | 中 | ⬜ |
| `SpriteBillboardParticleAccessor` | `SpriteBillboardParticle` | `TextureSheetParticle` | 高 | ⬜ |

### テクスチャと GL (`texture/`、`gl/`)

| Mixin | Yarn 1.21.1 | Mojang 1.21.1 | リスク | 状態 |
| --- | --- | --- | --- | --- |
| `GlStateManagerUploadMixin` | `GlStateManager` | `GlStateManager` | 高 | ⬜ |
| `RenderSystemMixin` | `RenderSystem` | `RenderSystem` | 高 | ⬜ |
| `NativeImageUploadMixin`、`NativeImageAccessor` | `NativeImage` | `NativeImage` | 高 | ⬜ |
| `NativeImageBackedTextureMixin` | `NativeImageBackedTexture` | `DynamicTexture` | 中 | ⬜ |
| `SpriteAtlasTextureMixin`、`SpriteAtlasAccessor` | `SpriteAtlasTexture` | `TextureAtlas` | 中 | ⬜ |
| `SpriteContentsUploadMixin`、`SpriteContentsAccessor` | `SpriteContents` | `SpriteContents` | 高 | ⬜ |
| `TextureManagerMixin` | `TextureManager` | `TextureManager` | 中 | ⬜ |
| `GlyphAtlasTextureMixin` | `GlyphAtlasTexture` | `FontTexture` | 高 | ⬜ |
| `PlayerSkinTextureMixin` | `PlayerSkinTexture` | `HttpTexture` (1.21.4 で廃止) | 高 | ⬜ |

### クライアント本体、入力、画面

| Mixin | Yarn 1.21.1 | Mojang 1.21.1 | リスク | 状態 |
| --- | --- | --- | --- | --- |
| `MinecraftClientMixin` (776 行) | `MinecraftClient` | `Minecraft` | 中 | ⬜ |
| `UnfocusedFramerateMixin` | `MinecraftClient` | `Minecraft` | 中 | ⬜ |
| `GameRendererMixin` | `GameRenderer` | `GameRenderer` | 高 | ⬜ |
| `WindowMixin` | `Window` | `Window` | 中 | ⬜ |
| `KeyboardMixin` | `Keyboard` | `KeyboardHandler` | 中 | ⬜ |
| `MouseMixin` | `Mouse` | `MouseHandler` | 中 | ⬜ |
| `DownloadingTerrainScreenMixin` | `DownloadingTerrainScreen` | `ReceivingLevelScreen` | 高 | ⬜ |
| `ClientWorldMixin` | `ClientWorld` | `ClientLevel` | 低 | ⬜ |

## Mixin 以外で影響が大きいファイル

- `entity/RemixVertexConsumerProvider.java`、`entity/RenderLayerUtils.java`:
  `VertexConsumerProvider` と `RenderLayer` の内部構造を access widener で
  読んでいる。1.21.2 と 1.21.5 の変更で作り直しが必要。
- `RtxRemixMod.java`: コマンド、キーバインド、ライフサイクル。Fabric API の
  名前とシグネチャの変更を追う。
- `block/`、`RtxRemixCommonMod.java`: ブロックとアイテムの登録。1.21.2 以降、
  登録時に `ResourceKey` を `Properties` に渡す必要がある (確度: 高)。
- `screen/`、`settings/`: `SimpleOption` (Mojang 名 `OptionInstance`) と
  ウィジェット。
- `logic/ConfLayerDriver.java`: バージョン文字列 `1.21.1` を含む。
- `src/main/resources/assets/`: ブロックモデル、ブロックステート。1.21.4 以降は
  アイテムに `assets/<ns>/items/*.json` が必要 (確度: 高)。

## 作業ログ

- 2026-10-24: リポジトリの規模と Mixin の対象を調査し、このメモを作成。
  ネットワークポリシーで Fabric と Mojang の配布元がブロックされているため、
  コードの変更は未着手。
