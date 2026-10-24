# PORTING.md: Minecraft 26.3 (Fabric) 移植メモ

このフォークで Minecraft 1.21.1 から 26.3 へ移植した作業の記録です。
`CLAUDE.md` と `AGENTS.md` は上流 (NVIDIA) のファイルなので変更せず、移植に
関する記録はこのファイルにまとめています。上流の `AGENTS.md` は計画やチェック
リストをリポジトリに置かないよう求めていますが、このファイルはフォークの判断で
置いています。上流へ PR を送るときはこのファイルを含めないでください。

## 現状 (2026-10-24)

コードの移植は一通り終わり、Linux 上で確認できる範囲の検証は通っています。
残っているのは、Windows 実機で RTX Remix ランタイムを載せて描画を目で確かめる
ことと、下の「未移植・既知の差分」に挙げた項目です。

このビルドは Windows と MSVC を前提にしています (`classes` が `setupRuntime`
に依存し、JNI ブリッジをコンパイルするため)。移植作業をした Linux 環境では
`-x setupRuntime` を付けて Java のコンパイルと JVM テストまでを確認しました。
Remix の描画は Windows で `tools\rtx.bat launch` を使わないと確認できません。

## 対象バージョン

| 項目 | 移植前 | 移植後 |
| --- | --- | --- |
| Minecraft | 1.21.1 | 26.3 |
| マッピング | Yarn `1.21.1+build.3` | なし (26.x は難読化なし。Mojang の名前そのまま) |
| Java | 21 | 25 (`java-runtime-epsilon`) |
| Fabric Loader | 0.16.5 | 0.19.5 |
| Fabric API | 0.116.10+1.21.1 | 0.165.0+26.3 |
| Loom | `fabric-loom` 1.15.5 | `net.fabricmc.fabric-loom` 1.18.4 (非リマップ版) |
| Gradle | 9.3.1 | 9.7.1 |
| Mixin / MixinExtras | | 0.8.7 / 0.5.5 |
| Fabric インストーラ | 1.0.1 | 1.0.1 のまま (26.3 + Loader 0.19.5 の導入を実際に確認) |

Java 25 は Mojang のバージョンメタデータ (`javaVersion`) と Prism のメタデータ
(`compatibleJavaMajors: [25]`) の両方で確認しています。

## 確認済みのこと

| 確認 | 結果 |
| --- | --- |
| `compileJava -x setupRuntime` | エラー 0 |
| Access widener の検証 (26.3 の jar に対して全行) | 問題 0 |
| Mixin の静的チェック (ASM で全ターゲットのメソッド、フィールド、`@At` の存在を確認) | 問題 0 |
| Mixin の監査 (`MixinEnvironment.audit()` で全ターゲットクラスを強制ロード、`required` 厳格) | 失敗 0 |
| JVM テスト `test -x setupRuntime` | 754 件すべて成功 |
| Python ツールのテスト (`tools/` で `uv run pytest`) | 992 件成功。失敗 3 件は上流 `main` でも Linux で失敗する Windows 専用テスト |
| Ruff format / lint | 問題なし |
| `installer.ps1` の構文 (PowerShell 7 のパーサ) | エラー 0 |
| ヘッドレス起動 (Xvfb + Mesa llvmpipe、`SDL_VIDEO_FORCE_EGL=1`) | ワールド参加まで例外なし。空、雲、エンティティ、HUD、手が描画される |
| 1.21.1 のワールドを 26.3 サーバーで開く | ゾーンファイルの扱いを確認 (下の「ゾーン」) |

ヘッドレス起動ではランタイムがないため、`rtxEnabled` が既定で真のまま地形が
見えません。これは上流でもランタイムなしで起こる挙動で、移植による退行では
ありません。`Requested post effect does not exist: minecraft:end_of_frame` は
開発環境の vanilla アセットに由来する警告です。

## Windows 実機で確かめること

`.\gradlew.bat build`、`.\gradlew.bat test`、`.\tools\rtx.bat launch`、
`.\gradlew.bat package -Parch=win64` の順に通してから、以下を見てください。

1. 地形: 各レイヤー (不透明、カットアウト、半透明、水面) が出るか。ブロックの
   置き換えと破壊で再キャプチャされるか。チャンク読み込み時に穴や継ぎ目がないか。
2. エンティティとブロックエンティティ: モブ、プレイヤー (スキン、マント、
   名札)、看板の文字、旗の模様、額縁、チェスト、ベル。視錐台の外のエンティティが
   影と反射に入るか。
3. 一人称: 手と持ったアイテム、松明などの手持ちライト、`Toggle Held Lights`。
   F1 で手が消えること (vanilla と同じ)。
4. テクスチャ: アニメーションするスプライト (水、溶岩、ポータル)、文字 (グリフ
   ページ)、地図、プレイヤースキン。
5. パーティクル、空 (オーバーワールド、ネザー、エンド)、雲 (Classic を含む)、
   ブロックの破壊ひび、選択ブロックの枠。
6. Copper Beacon の設置と動き。
7. オーバーレイ: Alt+X、設定画面の RTX Remix Graphics Settings ボタン、入力の
   取り込みと解放。
8. インストーラ: 新規インストール、1.21.1 版からのアップグレード (Prism
   インスタンスの Minecraft と Loader の版が付け替わること、ARM64 では Java の
   ピンが `jdk-25` に替わること)、アンインストール。

## 設計上の変更点

26.3 の描画系は 1.21.1 から大きく作り直されています。名前の置き換えでは済まず、
キャプチャ経路の多くを組み直しました。

### ビルドとツールチェーン

- 1.21.1 のまま Yarn から Mojang 名へ移した段階を挟み (`27bcf1d`)、その上で
  26.3 に上げました。
- 26.x は難読化がないので、Loom は非リマップ版の `net.fabricmc.fabric-loom`
  を使い、Access widener の名前空間は `official` です。`build/libs/` の JAR も
  Mojang 名のままです。
- `options.release = 25`。CI (`.github/workflows/build.yml`) も JDK 25 にしました。

### ウィンドウ、表示、入力

- 26.x はウィンドウが SDL3 になり、描画は renderpearl という GPU 抽象層を通ります。
  Remix は GL の画を受け取る前提なので、`PreferredGraphicsApiMixin` と
  `GlBackendMixin` で GL バックエンドに固定しています。
- 表示 (present) の差し替えは `RenderSystemMixin` から `GlSurfaceMixin`
  (`GlSurface.present`) に移しました。`WindowMixin` は不要になり削除しました。
- 入力は `KeyboardMixin` と `MouseMixin` のまま、SDL のイベント経路に合わせて
  書き直しています。

### 地形

- セクションの管理は `LevelRenderer` から `LevelExtractor` と `ViewArea` に移った
  ので、フックもそちらへ移しました (`LevelExtractorMixin`、`ViewAreaAccessor`、
  `SectionCopyMixin`)。
- vanilla の地形描画の取り消しは `ChunkSectionsToRender.renderLayers` を丸ごと
  止める形にしました (`ChunkSectionsToRenderMixin`)。このメソッドは呼び出し元が
  開いた `RenderPass` にユニフォームを設定するので、描画だけを止めることは
  しません。
- 再キャプチャは `WorldRenderer.reload()` ではなく `LevelExtractor.allChanged()`
  の前に `builtSections` を取る形になっています。

### エンティティ、ブロックエンティティ、アイテム

- 26.x は「抽出 (extract) してから提出 (submit) する」構造で、モデルは
  `EntityRenderState` などの状態から `SubmitNodeCollector` に提出されます。
  Remix 用には `RemixSubmitCollector` を作り、vanilla が提出する地点でそのまま
  受け取って `RemixVertexConsumerProvider` に流しています。影、アウトライン、
  ひび、パーティクル、ギズモなど Remix が使わないノードは捨てます。
- 状態から元のエンティティを辿れないので、`RenderStateOwnerMixin` で抽出時に
  持ち主を状態に記録しています。
- アイテムは `ItemStackRenderState` 経由になりました (`ItemModelResolverMixin`、
  `ItemStackRenderStateMixin`、`ItemLayerRenderStateMixin`)。
- 一人称は `FirstPersonHandsAndItemsRenderer.submitHandsWithItems` を
  `GameRenderer.renderItemInHand` 内で包んでいます。
- 名札の文字は vanilla 側でキャッシュの問題が直ったので、名前用の Mixin
  (`LivingEntityRendererNameMixin`、`PlayerEntityNameMixin`、
  `Deadmau5FeatureRendererMixin`) は削除しました。
- `PoseStack` は vanilla がエントリをプールするようになったため、再利用用の
  アクセサを削除しました。`BakedQuad` が record になったので、静的形状の
  キャッシュは境界をキーにしています。

### テクスチャ

- スプライトのアニメーションは GPU 側で進むようになったので、
  `SpriteAtlasTextureMixin` がフレーム更新の後に `AnimationState` から現在と次の
  フレームを読み、CPU で合成して Remix に送ります。
- スプライトに余白 (padding) が付いたため、座標は `AtlasSprites` で余白分ずらして
  います。CPU で組み立てる Remix 用アトラス (ブロック、パーティクル、エンティティ系)
  では、vanilla と同じく余白をスプライトの端の画素で埋めます
  (`AtlasSprites.padding`)。埋めずに透明な黒のままにしていた時期は、Remix の
  ミップマップに黒が混ざり、遠くの地形がグリッド状に見えていました。
- フォントのグリフは GPU 上で組み立てられるので、`CommandEncoderGlyphMirrorMixin`
  と `BitmapGlyphStagingMixin` で書き込みを CPU 側に写しています。
- プレイヤースキンは `DynamicTexture` になったので、`PlayerSkinTextureMixin` は
  不要になりました。

### パーティクル、空、雲、光

- パーティクルは `ParticleGroup` と `SingleQuadParticle` の `Layer` から
  地形アトラス用とパーティクルアトラス用に振り分けます。
- 空は `SkyRenderState` (角度は度からラジアンへ) と `DimensionType.Skybox` で
  分岐します。星は 26.3 で CPU の頂点列がなくなったので、同じ種 (10842) で
  CPU 側に作り直しています。
- 雲の色は `LevelRenderState.cloudColor` (ARGB、アルファ 0 は雲なし) から
  読みます。

### 統合サーバーと光

- サーバーのチャンク tick のリスト再利用 (`ChunkHolderMixin`、
  `ServerChunkManagerMixin`) は、26.3 の vanilla が `spawningChunks` を
  使い回すようになったので削除しました。
- `ChunkDataSenderMixin`、`ChunkSkyLightMixin`、`LightStorageMixin` は新しい
  シグネチャに合わせて移植しています。

### 設定画面

- `SimpleOption` は `OptionInstance` に、`clearAndInit()` は `rebuildWidgets()`
  に、遅延適用のスライダーは `OptionInstanceSliderButton.delayedApplyAt` に
  なりました。`docs/SETTINGS.md` も合わせて直しています。

### ゾーン

- 26.3 では保存データがワールド共通の `data/rtx-remix-renderer/zones.dat` に
  置かれます。1.21.1 版は `data/rtx_remix_zones.dat` に書いていました。
- 1.21.1 で作ったワールドを 26.3 のサーバーで開いて確かめたところ、vanilla の
  ワールド変換は `raids.dat` などを `dimensions/minecraft/<dim>/data/minecraft/`
  へ移しますが、知らないファイルである `rtx_remix_zones.dat` はそのまま残します。
- そこで最初のゾーン参照時に旧ファイルを新しい場所へ移すようにしました
  (`ZonePersistentState.moveLegacyFile`)。新しいファイルが既にあるときは何も
  しません。中身の形式は同じで、`SAVED_DATA_COMMAND_STORAGE` には 1.21.1
  以降のデータ修正が当たらないことも確認し、DataVersion 3955 のファイルを実際の
  `SavedDataStorage` と DataFixer で読むテストを付けています。

### パッケージとツール

- `packaging/installer.ps1`: 版の定数を更新し、`JAVA_MAJOR = 25` を導入。管理 JDK
  は `jdk-25` に置き、Microsoft ランチャー同梱の Java は
  `java-runtime-epsilon` を優先します。既存の Prism インスタンスを再利用する
  ときは、Minecraft と Fabric Loader の版が違えば `mmc-pack.json` を書き直し
  ます (`Update-PrismInstanceComponents`)。ARM64 で別の版の Java にピン留め
  されていれば、管理 JDK に付け替えます。`RECOMMENDED_JVM_ARGS` は変えて
  いません (26.3 のメタデータも `HeapDumpPath` を同じ綴りで渡しています)。
- `tools/`: `GAME_JAVA_MAJOR = 25`。ゲームウィンドウの判定はタイトル
  `Minecraft 26.3` に合うよう `^Minecraft\*? \d+\.\d` に広げました。
- プロファイラのスパン名 (`MinecraftClient.render.*`、`WorldRenderer.render`
  など) は、移植前後の記録を比べられるよう 1.21.1 時代の名前のままにしています。
  対応する 26.3 のメソッドは `tools/docs/PROFILING.md` に書きました。

## Mixin の増減

上流の 1.21.1 (client 70、common 3) から、client 65、common 1 になりました。

追加したもの:
`BitmapGlyphStagingMixin`、`ChunkSectionsToRenderMixin`、
`CommandEncoderGlyphMirrorMixin`、`GlBackendMixin`、`GlSurfaceMixin`、
`ItemLayerRenderStateMixin`、`ItemModelResolverMixin`、
`ItemStackRenderStateAccessor`、`ItemStackRenderStateMixin`、
`LevelExtractorAccessor`、`LevelExtractorMixin`、`ParticleGroupAccessor`、
`ParticleResourcesAccessor`、`PreferredGraphicsApiMixin`、
`RenderStateOwnerMixin`、`SectionCopyMixin`、`ViewAreaAccessor`。

削除したものと理由:

| Mixin | 理由 |
| --- | --- |
| `RenderSystemMixin`、`WindowMixin` | SDL3 と renderpearl に置き換わった。表示は `GlSurfaceMixin` へ |
| `GlStateManagerUploadMixin`、`NativeImageUploadMixin`、`SpriteContentsUploadMixin` | テクスチャの転送が renderpearl の `CommandEncoder` 経由になった |
| `VertexBufferMixin` | vanilla のバッファが renderpearl の `GpuBuffer` になった |
| `PlayerSkinTextureMixin` | スキンが `DynamicTexture` になった |
| `ItemRendererMixin` | アイテム描画が `ItemStackRenderState` 経由になった |
| `LivingEntityRendererNameMixin`、`PlayerEntityNameMixin`、`Deadmau5FeatureRendererMixin` | 名札の文字キャッシュの問題が vanilla で直った |
| `EntityRenderDispatcherAccessor`、`MatrixStackAccessor`、`MatrixStackEntryAccessor` | 抽出と提出の構造、`PoseStack` のプールで不要になった |
| `WeightedBakedModelMixin`、`MultipartBakedModelMixin` | モデルの選択が `collectParts` に変わった。割り当てを減らす最適化は未移植 |
| `ChunkRenderingDataPreparerMixin`、`RenderableChunksMixin`、`RenderedChunkMixin`、`BuiltChunkBuffersMixin`、`BuiltChunkStorageMixin` | 可視性ウォークの割り当てなし版と粗い視錐台インデックス。vanilla の `SectionOcclusionGraph` を使う (性能面の未移植) |
| `ClientChunkSnapshotAccess` | 描画領域のスナップショットがチャンク単位の `RenderChunk` からセクション単位の `SectionCopy` になった (`SectionCopyMixin`) |
| `ChunkHolderMixin`、`ServerChunkManagerMixin` | 26.3 の vanilla がリストを使い回すようになった |

## 未移植・既知の差分

優先度の高い順です。

1. **雨と雪** (`weather/RemixWeatherRenderer`): スタブです。RTX が有効な間は
   Remix の画が vanilla の雨と雪を覆うので、雨も雪も画面に出ません。1.21.1 版は
   vanilla の天候メッシュを取り込み、屈折用のコアシェーダーを使って生の GL で
   Remix の画の上に描いていました。26.3 は天候を頂点バッファとレンダー
   パイプラインで描き、深度が逆 (reversed depth) で、コアシェーダーの登録も
   なくなったので、renderpearl のパイプラインと逆深度に合わせた深度の初期化を
   用意する必要があります。地面に落ちる雨粒などのパーティクルは別経路で出ます。
2. **アイテムアトラスのパーティクル**: アイテムが割れるときなど、アイテム用
   アトラスを使うパーティクルは Remix に送っていません。
3. **アニメーションするスプライトの余白**: 水や溶岩などは毎フレーム本体だけを
   差し替えるので、余白は最初のフレームの端の画素のままです。遠くで縁の色が
   わずかにずれる程度で、黒い線にはなりません。
4. **Fabric Renderer API のメッシュ**: FRAPI のメッシュを持つアイテムやブロックは
   vanilla の経路で提出されたものだけを取り込みます。
5. **性能面で未移植のもの** (正しさには影響しない):
   - 可視性ウォークの割り当てなし版と粗い視錐台インデックス
     (`-Drtxremix.vanillaVisibilityWalk`、`-Drtxremix.logVisibleSet` も消えた)。
   - チャンク読み込み時の近傍の再ビルド抑制 (`NeighbourReflagFilter`)。26.3 では
     `LevelExtractor.setSectionDirtyWithNeighbors` が入口になるはずですが、
     抑制を誤るとテレインに穴が残り、Linux では目で確かめられないので、配線の
     切れた残骸ごと削除しました。1.21.1 版の実装は git 履歴の
     `BuiltChunkStorageMixin`、`BuiltChunkMixin`、`NeighbourReflagFilter` に
     あります。
   - モデル選択の割り当て削減 (`WeightedBakedModelMixin`、
     `MultipartBakedModelMixin`)。
   - vanilla の雲描画の抑止 (Remix が置き換える GL のフレームに描かれるだけ)。
6. **Prism のフォールバック版** (`PRISM_FALLBACK_VERSION = '9.1'`): 26.3 を
   起動できるかは未確認です。GitHub から最新版を取れる通常の経路では使われません。
7. **Java ソースのコメント**: Yarn 時代の名前がコメントに残っている箇所が
   あります。ドキュメント (`docs/`、`tools/docs/`、`CONTRIBUTING.md`) は
   26.3 の名前に直しました。

## 上流のファイルへの変更と取り込み時の注意

- `AGENTS.md` と `CLAUDE.md` は変えていません。そのため `AGENTS.md` には
  1.21.1 時代の記述が残っています。例: Java 21、`WorldRenderer.render()` と
  `setupTerrain`、`WeightedBakedModelMixin`、`MultipartBakedModelMixin`、
  `ChunkRenderingDataPreparerMixin`、`FarChunkProbeTest`、
  `NeighbourReflagFilter`、`-Drtxremix.vanillaVisibilityWalk`。このフォークでは
  このファイルとソースの方を正としてください。
- `CHANGELOG.md` は上流の履歴なので、移植の項目は足していません。
- 版や手順を書いたドキュメント (`README.md`、`docs/`、`tools/docs/`、
  `packaging/*.md`、`CONTRIBUTING.md`、`THIRD_PARTY_NOTICES.md`、
  `rtxremix-java/README.md`) は 26.3 に合わせて直しています。上流の更新を
  取り込むときは、これらと `installer.ps1`、`build.gradle` で衝突しやすいです。
- 上流の `CONTRIBUTING.md` はすべてのコミットに DCO の `Signed-off-by` を
  求めています。このブランチのコミットには付いていません。DCO は人が内容を
  確認して署名するものなので、上流へ送る場合は確認のうえ
  `git rebase --signoff` などで付けてください。

## 検証の手順 (Linux)

```bash
./gradlew compileJava -x setupRuntime
./gradlew test -x setupRuntime
cd tools && uv run --frozen pytest -q && uv run --frozen ruff check . && uv run --frozen ruff format --check .
```

`tools/` には uv 0.13 以上と Python 3.15 以上が必要です (uv が Python を
用意します)。ヘッドレスで起動するときは Xvfb と Mesa を使い、
`SDL_VIDEO_FORCE_EGL=1` を付けます。クイックプレイで止まらないよう
`run/options.txt` に `onboardAccessibility:false` を書いておきます。

## 作業ログ

| コミット | 内容 |
| --- | --- |
| `c822f52` | PORTING.md を追加 |
| `27bcf1d` | 1.21.1 のまま Yarn から Mojang 名へ |
| `7d8af06` | 26.3 へ: ビルド、ウィンドウ、表示、入力 |
| `268cf48` | 地形のキャプチャと `LevelExtractor` のフック |
| `e080d83` | Copper Beacon のブロックとデータ |
| `ed5bc60` | 設定、カメラ、ゾーン、ベンチマーク |
| `062a4ed`、`cb29a4b` | アトラスのアニメーション転送とグリフの写し |
| `a2fce6d` | エンティティ、ブロックエンティティ、アイテム、一人称 |
| `fd5d058` | パーティクル、空、雲、光、天候のフック |
| `da6156d` | 26.3 に存在しない Mixin ターゲットの修正 (静的チェックと監査で検出) |
| `2d8aa9f` | JVM テストの移植 |
| `3ba528b` | 閉じた画像を持つ動的テクスチャを解放済みとして扱う (テストで検出) |
| `9059e09` | パッケージ、ツール、ドキュメントを 26.3 と Java 25 へ |
| `5d46ffe` | 配線の切れた近傍再ビルド抑制を削除、ドキュメントを 26.3 の名前へ |
| `3d7d799` | 1.21.1 のゾーンファイルを 26.3 の保存先へ移す |
