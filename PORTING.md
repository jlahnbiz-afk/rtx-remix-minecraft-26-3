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
| JVM テスト `test -x setupRuntime` | 760 件すべて成功 |
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

- 1.21.1 のまま Yarn から Mojang 名へ移した段階を挟み (`77c0ac8`)、その上で
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
- 26.1 以降の vanilla は、面ごとのレイヤーをテクスチャの透明度から決めます
  (`ChunkSectionLayer.byTransparency`)。アルファ 1〜254 の画素が1つでもあれば
  半透明です。リソースパックの縁のぼかしだけでドアの窓や花が半透明になり、
  Remix の屈折する半透明マテリアル (色付きガラス用) で描かれてガラスのように
  見えていました。そこで、半透明の画素が完全透明の画素以上あり、かつ全体の
  1/8 以上あるスプライト (色付きガラス、氷、スライムなど) だけを半透明のまま
  にし、それ以外はカットアウトにします (`BlockTextureManager.readsAsTranslucent`)。
  1.21.1 はブロックごとの固定表でドアや花をカットアウトにしていたので、その
  見た目に戻ります。手に持ったブロックも同じ判定です。
- セクションのバケツ (マテリアルごとの書き込み先) は「4096 ブロック × 6 面」で
  伸びるのを止めていて、細かいモデルのリソースパック (LRT 128x のフェンスなど)
  でセクションのビルドが `IndexOutOfBoundsException` で落ちていました。上流の
  1.21.1 版にも同じ上限があります。今は 1 GiB まで伸び、1ブロックの一時領域も
  128 面を超えて伸びます。どちらかの上限を超えた面はクラッシュさせずに捨て、
  `Chunk.DroppedQuads` カウンタで数えます (`DirectQuadWriter`)。
- 葉やガラスの向こうのハーフブロックの側面が、見る角度によって抜けていました。
  Remix は地形を両面で描くので、葉の奥側の面がハーフブロックの側面とちょうど
  同じ平面に重なります。ランタイムは切り抜きの透明な画素を通るとき、少し先から
  光線を出し直します (ずらす量はワールド座標に比例します)。先に葉の透明な画素に
  当たった光線は、そのまま側面を通り越していました。そこで、遮蔽しないブロック
  (`canOcclude()` が偽) の面のうち、隣のブロックも向かい合う面を描くものは、
  隣へ 1/512 ブロックずらします (`BlockQuadEmitter.meetsNeighbourFace`)。上流の
  1.21.1 版でも同じ形になります。

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
- Fabric Renderer API (FRAPI) のメッシュ: 地形は `emitQuads` で取り込みます。
  アイテムとブロックモデルは、Fabric が `SubmitNodeCollector` に足すメッシュ付きの
  `submitItem` と `submitBlockModel` を `RemixSubmitCollector` で受け、メッシュの
  各面を `QuadView.toBakedQuad` で vanilla と同じ形にして取り込みます (既定の実装は
  メッシュを捨てます)。
- 一人称は `FirstPersonHandsAndItemsRenderer.submitHandsWithItems` を
  `GameRenderer.renderItemInHand` 内で包んでいます。
- 名札の文字は vanilla 側でキャッシュの問題が直ったので、名前用の Mixin
  (`LivingEntityRendererNameMixin`、`PlayerEntityNameMixin`、
  `Deadmau5FeatureRendererMixin`) は削除しました。
- `PoseStack` は vanilla がエントリをプールするようになったため、再利用用の
  アクセサを削除しました。`BakedQuad` が record になったので、静的形状の
  キャッシュは境界をキーにしています。
- 一人称の腕は 26.x から袖と1つのモデル部品になり、`entityTranslucent` で
  描かれます。一人称モデルは 0.1 倍に縮めて描くので、袖と腕の隙間は 2 mm 足らず
  です。原点から離れると、透明な袖の画素を通り越すときのずらし量がこの隙間を
  超え、光線が腕の内側から腕の奥の面に当たって真っ黒に見えていました。一人称の
  腕には、袖の透明な画素に下の腕の画素を合成したスキンの複製を使います
  (`FirstPersonSleeves`)。見た目は vanilla の重ね方と同じで、袖の位置に
  描かれる分だけ 0.25 px 外側になります。上流の 1.21.1 版も同じ寸法なので、
  同じ症状が出るはずです。

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

### 雨と雪

- Remix の画が vanilla の雨と雪を覆うので、合成の後に描き直しています
  (`weather/RemixWeatherRenderer`)。vanilla の `WeatherEffectRenderer.prepare` が
  毎フレーム作る頂点 (カメラ相対、雨の列の後に雪の列、PARTICLE 形式) を
  `WeatherEffectRendererMixin` で写し、vanilla 自身の天候描画は
  `WorldRendererMixin` で止めます (通常の透過と OIT の両方)。
- 26.3 はコアシェーダーの登録がなくなったので、1.21.1 の屈折シェーダーを
  自前の GL プログラムに移しました。雪は vanilla のパーティクル用シェーダーと
  同じく、テクスチャ × 頂点色 × ライトマップで描きます。
- Remix の深度との前後判定は、深度バッファを作らずにフラグメントシェーダー内で
  インターロップ深度テクスチャを読んで行います。投影は Remix のカメラと同じ
  near 0.05 / far 1000 で、GL バックエンドが `glClipControl(ZERO_TO_ONE)` を
  設定していればそれに合わせます。vanilla の逆深度は使いません。
- GL バックエンドがキャッシュするステート (フレームバッファ、アクティブな
  テクスチャユニットとその束縛、深度、ブレンド、カリング、シザー、カラーマスク)
  は `GlStateManager` 経由で設定し、キャッシュしないもの (プログラム、頂点配列、
  サンプラー、ビューポート) は元に戻します。シェーダーは Mesa の GLSL
  コンパイラでコンパイルとリンクを確認しましたが、描画結果は Windows で
  確かめる必要があります。

### 日本語化 (このフォークで追加)

- `ja_jp.json` は `en_us.json` のすべてのキーを訳しています。RTX 切り替えキーの
  表示と、メニューを開けないときのメッセージも言語ファイル経由にしました。
  `/rtx` コマンドの出力は開発用なので英語のままです。
- 日本語の文字は Unifont で描かれて幅が広いので、ボタンや設定の行に収まる
  よう短めの言葉にしています。キーと `%s` の数が英語とそろっていることを
  `LanguageFilesTest` で確かめています。上流で `en_us.json` にキーが増えたら、
  `ja_jp.json` にも足してください。

### 雨の水たまりと波紋 (このフォークで追加)

上流にはない機能です。地形の上に 2 種類の薄い膜を敷き、天気は膜のテクスチャの
中身だけで表します (`RainSurfaceTextures`)。材質は作ったあと一切変えません。
最初の版は材質のその場更新 (`updateMaterial`) でしきい値や法線を切り替えて
いましたが、法線の差し替えが反映されず晴れても波紋が残ったので、毎フレーム
動いているアトラスのアニメーションと同じ `updateTextureFromMemory` の経路に
寄せました。
- 水たまり: セクションを作るとき、空に面した平らな上面 (遮蔽するブロックで、
  上面が全面、その列で雨を止める一番上のブロック、雪ではなく雨が降る場所) に、
  1/64 ブロック浮かせた板を置きます。「雨を止める一番上のブロック」は vanilla
  が雨の降り先に使う `MOTION_BLOCKING` の高さマップで判定します (作業スレッド
  から光は読まない決まりなので、空の明るさは使いません)。
- 形は 2 つの場の掛け算です (`PuddleField`)。粗い「濡れる区域」は座標のノイズ
  で決まり、板の頂点アルファに入ります。区域の外には板を置かず、縁で 0 から
  なめらかに上がります。細かい水たまりの形は 16 ブロック周期で敷き詰める
  256x256 のマスク (1 ブロック 16 テクセル) に入っていて、板はワールド座標の
  UV でそれを貼ります。地面が濡れるとマスクのしきい値が下がり、水たまりが
  深いところから丸く、縁をぼかしながら広がります (区域の中の最大 3 割)。
- 材質はつるつる (粗さ 0.02) の不透明材質にアルファブレンドを付けたもので、
  Remix の一次レイは不透明度を確率的に混ぜるので、ぼかした縁は地面が少しずつ
  水に沈むように見えます。
- 降り始めて 20 秒で満水、止んで 30 秒で乾きます (`RainSurfaces`)。
- 波紋: 雨粒の輪の 64 コマ (2 秒でループ、毎秒 32 コマ) を起動時に別スレッド
  で作ります (`RainRipples`、1 ブロックあたり毎秒 10 滴、輪は 0.45 秒で消える)。
  フル強度のコマを前もって作っておくので、雨の最中は 1 コマあたりコピーだけ
  です。輪の法線マップは水たまりの板と、空に面した水面の少し上に敷く
  「波紋の膜」(`rain_ripple` バケツ) が共有します。波紋の膜は輪のところだけ
  不透明度が付き、それ以外は透けて元の水面が見えます。
- 波紋の強さは雨の強さそのものなので、晴れていくと弱まり、止むと法線を平らに、
  膜を透明に戻します。
- 判定は地形を作った時点のものなので、あとから上にブロックを置いても、その
  セクションが作り直されるまで板は残ります (晴れていれば透明です)。
- `-Drtxremix.rain.puddles=false`、`-Drtxremix.rain.ripples=false` で切れます。
  両方 false にすると板もテクスチャも作りません。

### ブロックの光の倍率 (このフォークで変更)

上流では「ブロックの光」の倍率を変えて画面を閉じるたびに、読み込み済みの地形を
全部作り直していました (`rebuildLoadedTerrain`)。倍率は光の明るさにしか効かない
のに、地形のメッシュと BLAS を全部壊して作り直していました。何度か変えると
VRAM が 20 GB ほどまで膨らむという報告があり、Java 側で解放漏れは見つからな
かったので、ランタイムが古い地形を解放し終える前に新しい地形が届いていると
見ています (ランタイム側では未確認)。今は地形に触らず、既存のネイティブの光をその場で更新します
(`ChunkManager.refreshBlockLightIntensities`)。
- `TerrainLight` が自分を作ったルールの出力 (`LightEntry`) を持ち、それから
  明るさを計算し直します。更新は光の作成と同じ予算 (1 フレーム 32 回) で
  少しずつ進むので、光の多いワールドでは数秒かけて切り替わります。
- 光のフィンガープリントに倍率を入れたので、変更後に作り直されたセクションの
  光も古い明るさのまま残りません。
- 「ブロックの発光の強さ」(材質の設定) は今も材質と地形を作り直します。

### パーティクル、空、雲、光

- パーティクルは `ParticleGroup` と `SingleQuadParticle` の `Layer` から
  地形、パーティクル、アイテムの各アトラス用に振り分けます。26.3 でアイテム用
  アトラスが独立したので、`ParticleAtlasManager` がアイテムアトラスの
  マテリアルも作り、アイテムが割れたときの破片などはブロックの破片と同じく
  アルファテストで描きます。
- 空は `SkyRenderState` (角度は度からラジアンへ) と `DimensionType.Skybox` で
  分岐します。星は 26.3 で CPU の頂点列がなくなったので、同じ種 (10842) で
  CPU 側に作り直しています。
- 雲の色は `LevelRenderState.cloudColor` (ARGB、アルファ 0 は雲なし) から
  読みます。Remix の雲が正常に動いている間は、vanilla の雲描画 (通常の透過と
  OIT の両方) を止めます。

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
  されていれば、管理 JDK に付け替えます。
- ヒープはフォークの判断で、最小・最大とも 16 GB に固定しました
  (`-Xms16G -Xmx16G`)。上流はランチャー既定の 2 GB でした。G1 の設定と
  `HeapDumpPath` (26.3 のメタデータも同じ綴り) はそのままです。
  `RECOMMENDED_JVM_ARGS` と `build.gradle` の `loom.runs.client.vmArgs` を
  揃えて変え、2 GB の組は `PREVIOUS_RECOMMENDED_JVM_ARGS` に足したので、以前の
  インストーラーが書いた設定は確認なしで 16 GB に置き換わります。Prism では
  インスタンスのメモリ設定が最小 16384 MiB、最大 16384 MiB になります。
  Prism のメモリ設定で自分で選んだ値は、これまでどおり残ります。
- `tools/`: `GAME_JAVA_MAJOR = 25`。ゲームウィンドウの判定はタイトル
  `Minecraft 26.3` に合うよう `^Minecraft\*? \d+\.\d` に広げました。
- プロファイラのスパン名 (`MinecraftClient.render.*`、`WorldRenderer.render`
  など) は、移植前後の記録を比べられるよう 1.21.1 時代の名前のままにしています。
  対応する 26.3 のメソッドは `tools/docs/PROFILING.md` に書きました。

## Mixin の増減

上流の 1.21.1 (client 70、common 3) から、client 66、common 1 になりました。

追加したもの:
`BitmapGlyphStagingMixin`、`ChunkSectionsToRenderMixin`、
`CommandEncoderGlyphMirrorMixin`、`GlBackendMixin`、`GlSurfaceMixin`、
`ItemLayerRenderStateMixin`、`ItemModelResolverMixin`、
`ItemStackRenderStateAccessor`、`ItemStackRenderStateMixin`、
`LevelExtractorAccessor`、`LevelExtractorMixin`、`ParticleGroupAccessor`、
`ParticleResourcesAccessor`、`PreferredGraphicsApiMixin`、
`RenderStateOwnerMixin`、`SectionCopyMixin`、`ViewAreaAccessor`、
`WeatherEffectRendererMixin`。

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

1. **アニメーションするスプライトの余白**: 水や溶岩などは毎フレーム本体だけを
   差し替えるので、余白は最初のフレームの端の画素のままです。遠くで縁の色が
   わずかにずれる程度で、黒い線にはなりません。
2. **性能面で未移植のもの** (正しさには影響しない):
   - 可視性ウォークの割り当てなし版と粗い視錐台インデックス
     (`-Drtxremix.vanillaVisibilityWalk`、`-Drtxremix.logVisibleSet` も消えた)。
   - チャンク読み込み時の近傍の再ビルド抑制 (`NeighbourReflagFilter`)。26.3 では
     `LevelExtractor.setSectionDirtyWithNeighbors` が入口になるはずですが、
     抑制を誤るとテレインに穴が残り、Linux では目で確かめられないので、配線の
     切れた残骸ごと削除しました。1.21.1 版の実装は git 履歴の
     `BuiltChunkStorageMixin`、`BuiltChunkMixin`、`NeighbourReflagFilter` に
     あります。
   - モデル選択の割り当て削減 (`WeightedBakedModelMixin`、
     `MultipartBakedModelMixin`)。26.3 は部品の収集が面ごとではなくブロックごとに
     なり、重み付きの選択は割り当てなし、multipart もブロックごとに `BitSet` と
     イテレータを作るだけになったので、移植していません。

3. **排他フルスクリーンと DLSS フレーム生成**: Minecraft の「排他フルスクリーン」
   をオンにしたまま DLSS フレーム生成を有効にすると、スワップチェーンが作り
   直された十数秒後に `VK_ERROR_DEVICE_LOST` で落ちることがあります (実機で
   確認、ランタイムかドライバー側の問題)。排他フルスクリーンをオフにした
   ボーダーレスでは落ちません。Remix の出力はランタイム自身の Vulkan スワップ
   チェーンから出るので (ログの `Exclusive FS: 2` は排他を許可しない設定)、
   排他にしても画面の解像度が切り替わるだけです。

## 上流のファイルへの変更と取り込み時の注意

- `AGENTS.md` と `CLAUDE.md` は変えていません。そのため `AGENTS.md` には
  1.21.1 時代の記述が残っています。例: Java 21、`WorldRenderer.render()` と
  `setupTerrain`、`WeightedBakedModelMixin`、`MultipartBakedModelMixin`、
  `ChunkRenderingDataPreparerMixin`、`FarChunkProbeTest`、
  `NeighbourReflagFilter`、`-Drtxremix.vanillaVisibilityWalk`。このフォークでは
  このファイルとソースの方を正としてください。
- `CHANGELOG.md` は上流の履歴なので、移植の項目は足していません。
- Mixin のクラス名 (`WorldRendererMixin`、`BuiltChunkMixin` など) とプロファイラの
  スパン名は、上流との差分と記録の比較のために 1.21.1 時代の名前のままです。
  コメントとドキュメントの中の vanilla のクラス名は 26.3 の名前に直しました。
- Prism のフォールバック版 (`PRISM_FALLBACK_VERSION`) は 11.1.1 にしました。
- `RECOMMENDED_JVM_ARGS` は 16 GB 固定にしています (上流は 2 GB)。上流が
  この行を変えたら、上流の新しい組を `PREVIOUS_RECOMMENDED_JVM_ARGS` に足し、
  ヒープを 16 GB にした組を現行にしてください。`build.gradle` の `vmArgs` も
  同じ値にします。
  チェックサムのサイドカーがないため、SHA-256 はリリースの zip から計算しています。
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
| `ab652bb` | PORTING.md を追加 |
| `77c0ac8` | 1.21.1 のまま Yarn から Mojang 名へ |
| `41a52e4` | 26.3 へ: ビルド、ウィンドウ、表示、入力 |
| `eaf671d` | 地形のキャプチャと `LevelExtractor` のフック |
| `78b5cd9` | Copper Beacon のブロックとデータ |
| `fe5d799` | 設定、カメラ、ゾーン、ベンチマーク |
| `8b5e459`、`ae02a6c` | アトラスのアニメーション転送とグリフの写し |
| `f133d4b` | エンティティ、ブロックエンティティ、アイテム、一人称 |
| `9e5d5bc` | パーティクル、空、雲、光、天候のフック |
| `22e176a` | 26.3 に存在しない Mixin ターゲットの修正 (静的チェックと監査で検出) |
| `758c58f` | JVM テストの移植 |
| `e87e20a` | 閉じた画像を持つ動的テクスチャを解放済みとして扱う (テストで検出) |
| `67d65b0` | パッケージ、ツール、ドキュメントを 26.3 と Java 25 へ |
| `03b9459` | 配線の切れた近傍再ビルド抑制を削除、ドキュメントを 26.3 の名前へ |
| `08ae71e` | 1.21.1 のゾーンファイルを 26.3 の保存先へ移す |
| `09a373f` | PORTING.md を移植完了後の内容に書き直す |
| `74d0eea` | Remix 用に組んだアトラスのスプライト余白を埋める (遠景のグリッド模様) |
| `1a9e4ae` | 雨と雪を Remix の画像の上に描く |
| `6c1fc6a` | 空の生成後にアクティブなテクスチャユニットを戻す |
| `32b2b56` | アイテムアトラスのパーティクルを Remix へ送る |
| `e2d5baa` | Remix が雲を描くときは vanilla の雲を描かない |
| `30ec79b` | Prism の最新版が取れないときは 11.1.1 を使う |
| `6af31ad` | Java のコメントを 26.3 のクラス名へ |
| `3c30da6` | 縁のぼかしだけのスプライトを屈折する半透明にしない (ドア、花) |
| `372465d` | Fabric レンダラー API のメッシュをアイテムとブロックモデルで拾う |
| `bac019f` | 地形のバケツを 6 面/ブロックの上限を超えて伸ばす (フェンスのクラッシュ) |
| `40c7a2f` | ヒープを最小・最大とも 16 GB に固定 |
| `67801d7` | 一人称の透明な袖に腕を描き込む (腕が黒くなる件) |
| `830ae62` | 隣と向かい合う透ける面を同じ平面からずらす (葉の向こうのハーフブロック) |
| `acdd91e` | 雨の水たまりと、空に面した水面の雨の波紋 |
| `9f85097` | `26.3` ブランチへのプッシュで Actions のビルドとチェックを走らせ、JAR を保存 |
| `fdfbddf` | 日本語化 (`ja_jp.json`) |
| `03aa5ec` | 雨の水たまりと波紋を作り直す (丸くぼかした水たまり、波紋の膜、晴れたら止まる波紋) |
| `bf601f7` | ブロックの光の倍率を地形を作り直さずにその場で反映する (VRAM が膨らむ件) |
