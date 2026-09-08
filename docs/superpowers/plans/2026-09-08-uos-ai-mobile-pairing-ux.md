# UOS AI Mobile Pairing UX Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (\`- [ ]\`) syntax for tracking.

**Goal:** Make the desktop Mobile Companion page clear in light and dark themes, prepare its secure Tailnet listener from one explicit user action, and make Android pairing camera-first.

**Architecture:** A new C++ bootstrap service parses fixed \`tailscale status --json\` output and, only when asked, invokes fixed \`tailscale cert\` arguments. The Application layer validates workspaces and persists only successfully started WSS settings. The Vue page is a theme-aware three-card workflow powered by a pure helper; Android keeps protocol v1 but moves paste entry behind the scanner.

**Tech Stack:** Qt Core/Network/WebSockets/Test, QProcess, Vue 3 TSX/CSS, Node 24, Kotlin/Compose/CameraX/ZXing, Android API 36.

## Global constraints

- Production connections remain only \`wss://<ASCII *.ts.net hostname>:<port>\`: no public, LAN, wildcard, cleartext, or certificate-bypass fallback.
- Only an explicit desktop action may call \`tailscale status\` or \`tailscale cert\`; use QProcess program and argument lists, never a shell.
- PEM outputs stay under \`QStandardPaths::AppDataLocation/mobile-companion/tls\`; never log pairing secrets, grant tokens, PEM data, or raw Tailscale stderr.
- The existing selected-workspace allowlist and one-time five-minute QR invitation are retained.
- Manual host, port, certificate, and private-key values remain inside Advanced connection settings.
- Do not modify host worktree user changes in \`translations/uos-ai-assistant_zh_CN.ts\`, \`web/dist/index.html\`, or \`plugin-aibar/translations/\`.
- Use Node 24 via \`source /home/toberyan/.config/nvm/nvm.sh && nvm use 24\`. Keep Android \`compileSdk\` and \`targetSdk\` at 36 and add no Google Play dependency.

## Task 1: Test and implement isolated Tailnet discovery

**Files:**

- Create: \`/home/toberyan/orca/workspaces/uos-ai-0908/uos-ai-ios-tailscale-host/src/remote/tailscalebootstrap.h\`
- Create: \`/home/toberyan/orca/workspaces/uos-ai-0908/uos-ai-ios-tailscale-host/src/remote/tailscalebootstrap.cpp\`
- Create: \`/home/toberyan/orca/workspaces/uos-ai-0908/uos-ai-ios-tailscale-host/tests/test_tailscalebootstrap.cpp\`
- Modify: \`/home/toberyan/orca/workspaces/uos-ai-0908/uos-ai-ios-tailscale-host/tests/CMakeLists.txt\`

**Interfaces:** This task produces \`TailnetIdentity { QHostAddress address; QString hostname; }\`, \`TailnetDiscoveryResult { std::optional<TailnetIdentity> identity; QString error; }\`, and \`RemoteCompanionBootstrap::parseStatusJson(const QByteArray &)\` for Tasks 2–3.

- [ ] **Step 1: Write failing discovery tests**

\`\`\`cpp
class TailnetBootstrapTest : public QObject
{
    Q_OBJECT
private slots:
    void parsesDnsNameAndTailnetIpv4();
    void trimsTerminalDot();
    void rejectsPublicHostnameAndMissingTailnetIp();
};

void TailnetBootstrapTest::parsesDnsNameAndTailnetIpv4()
{
    const auto result = RemoteCompanionBootstrap::parseStatusJson(R"({
      "Self":{"DNSName":"uos-ai.example.ts.net.","TailscaleIPs":["100.64.0.7"]}
    })");
    QVERIFY(result.identity);
    QCOMPARE(result.identity->hostname, QStringLiteral("uos-ai.example.ts.net"));
    QCOMPARE(result.identity->address, QHostAddress(QStringLiteral("100.64.0.7")));
}
\`\`\`

Append this exact target:

\`\`\`cmake
add_remote_qtest(test_tailscale_bootstrap
    test_tailscalebootstrap.cpp
    ${CMAKE_SOURCE_DIR}/src/remote/tailscalebootstrap.cpp
)
\`\`\`

- [ ] **Step 2: Verify the test is red**

\`\`\`sh
cmake -S /home/toberyan/orca/workspaces/uos-ai-0908/uos-ai-ios-tailscale-host -B /home/toberyan/orca/workspaces/uos-ai-0908/uos-ai-ios-tailscale-host/build-remote-tests -DBUILD_TESTING=ON
cmake --build /home/toberyan/orca/workspaces/uos-ai-0908/uos-ai-ios-tailscale-host/build-remote-tests --target test_tailscale_bootstrap
\`\`\`

Expected: compilation fails because \`tailscalebootstrap.h\` does not exist.

- [ ] **Step 3: Implement parser and fixed runner**

\`\`\`cpp
struct TailnetIdentity { QHostAddress address; QString hostname; };
struct TailnetDiscoveryResult { std::optional<TailnetIdentity> identity; QString error; };
struct CommandResult { int exitCode; QByteArray standardOutput; QByteArray standardError; };

class RemoteCompanionBootstrap final {
public:
    using CommandRunner = std::function<CommandResult(const QString &, const QStringList &)>;
    explicit RemoteCompanionBootstrap(CommandRunner runner = runCommand);
    static TailnetDiscoveryResult parseStatusJson(const QByteArray &json);
    TailnetDiscoveryResult discover() const;
private:
    static CommandResult runCommand(const QString &program, const QStringList &arguments);
    CommandRunner m_runner;
};
\`\`\`

Read only \`Self.DNSName\` and \`Self.TailscaleIPs\`; trim one final dot; select the first value accepted by \`RemoteCompanionServer::isTailnetAddress\`; then require \`RemoteCompanionServer::isTailnetHostname\`. \`discover\` must call exactly \`tailscale\` with \`{"status", "--json"}\`. The default runner must call \`QProcess::start(program, arguments)\`, wait at most 15 seconds, kill on timeout, and return fixed public errors instead of subprocess output.

- [ ] **Step 4: Verify green**

\`\`\`sh
cmake --build /home/toberyan/orca/workspaces/uos-ai-0908/uos-ai-ios-tailscale-host/build-remote-tests --target test_tailscale_bootstrap
ctest --test-dir /home/toberyan/orca/workspaces/uos-ai-0908/uos-ai-ios-tailscale-host/build-remote-tests -R '^test_tailscale_bootstrap$' --output-on-failure
\`\`\`

Expected: valid Tailnet data passes; public/missing values yield no identity; tests execute no live command.

- [ ] **Step 5: Commit**

\`\`\`sh
git -C /home/toberyan/orca/workspaces/uos-ai-0908/uos-ai-ios-tailscale-host add src/remote/tailscalebootstrap.h src/remote/tailscalebootstrap.cpp tests/test_tailscalebootstrap.cpp tests/CMakeLists.txt
git -C /home/toberyan/orca/workspaces/uos-ai-0908/uos-ai-ios-tailscale-host commit -m 'feat: detect tailnet identity for mobile pairing'
\`\`\`

## Task 2: Test and implement explicit certificate preparation

**Files:**

- Modify: \`/home/toberyan/orca/workspaces/uos-ai-0908/uos-ai-ios-tailscale-host/src/remote/tailscalebootstrap.h\`
- Modify: \`/home/toberyan/orca/workspaces/uos-ai-0908/uos-ai-ios-tailscale-host/src/remote/tailscalebootstrap.cpp\`
- Modify: \`/home/toberyan/orca/workspaces/uos-ai-0908/uos-ai-ios-tailscale-host/tests/test_tailscalebootstrap.cpp\`

**Interfaces:** Produces \`PreparedTailnetTls { TailnetIdentity identity; RemoteTlsConfig tls; }\` and \`TailnetPrepareResult { std::optional<PreparedTailnetTls> prepared; QString error; }\` for Task 3.

- [ ] **Step 1: Write failing preparation tests**

\`\`\`cpp
void TailnetBootstrapTest::issuesCertificateWithFixedArguments()
{
    QStringList certificateArguments;
    RemoteCompanionBootstrap bootstrap([&](const QString &, const QStringList &arguments) {
        if (arguments == QStringList {"status", "--json"})
            return CommandResult {0, R"({"Self":{"DNSName":"uos-ai.example.ts.net.","TailscaleIPs":["100.64.0.7"]}})", {}};
        certificateArguments = arguments;
        QFile certificate(arguments.at(2));
        QFile privateKey(arguments.at(4));
        QVERIFY(certificate.open(QIODevice::WriteOnly));
        QVERIFY(privateKey.open(QIODevice::WriteOnly));
        return CommandResult {0, {}, {}};
    });
    QTemporaryDir directory;
    QVERIFY(bootstrap.prepare(directory.path()).prepared);
    QCOMPARE(certificateArguments.first(), QStringLiteral("cert"));
    QCOMPARE(certificateArguments.last(), QStringLiteral("uos-ai.example.ts.net"));
}

void TailnetBootstrapTest::returnsNoTlsWhenCertificateCommandFails();
\`\`\`

- [ ] **Step 2: Verify the test is red**

\`\`\`sh
cmake --build /home/toberyan/orca/workspaces/uos-ai-0908/uos-ai-ios-tailscale-host/build-remote-tests --target test_tailscale_bootstrap
ctest --test-dir /home/toberyan/orca/workspaces/uos-ai-0908/uos-ai-ios-tailscale-host/build-remote-tests -R '^test_tailscale_bootstrap$' --output-on-failure
\`\`\`

Expected: compilation fails because \`prepare\` and its result types are absent.

- [ ] **Step 3: Implement only the preparation primitive**

\`\`\`cpp
struct PreparedTailnetTls { TailnetIdentity identity; RemoteTlsConfig tls; };
struct TailnetPrepareResult { std::optional<PreparedTailnetTls> prepared; QString error; };

TailnetPrepareResult RemoteCompanionBootstrap::prepare(const QString &appDataDirectory) const;
\`\`\`

Make \`QDir(appDataDirectory).filePath("mobile-companion/tls")\` and issue exactly:

\`\`\`cpp
{QStringLiteral("cert"), QStringLiteral("--cert-file"), certificatePath,
 QStringLiteral("--key-file"), privateKeyPath, identity.hostname}
\`\`\`

After zero exit status, both outputs must be regular readable files. Restrict the key file to owner read/write where supported. On any error return exactly \`Certificate preparation failed\` and no partial TLS paths. This service never persists settings or starts a listener.

- [ ] **Step 4: Verify green**

\`\`\`sh
cmake --build /home/toberyan/orca/workspaces/uos-ai-0908/uos-ai-ios-tailscale-host/build-remote-tests --target test_tailscale_bootstrap
ctest --test-dir /home/toberyan/orca/workspaces/uos-ai-0908/uos-ai-ios-tailscale-host/build-remote-tests -R '^test_tailscale_bootstrap$' --output-on-failure
\`\`\`

Expected: the injected runner sees the exact command order and failed issuance has no prepared TLS result.

- [ ] **Step 5: Commit**

\`\`\`sh
git -C /home/toberyan/orca/workspaces/uos-ai-0908/uos-ai-ios-tailscale-host add src/remote/tailscalebootstrap.h src/remote/tailscalebootstrap.cpp tests/test_tailscalebootstrap.cpp
git -C /home/toberyan/orca/workspaces/uos-ai-0908/uos-ai-ios-tailscale-host commit -m 'feat: prepare local tailnet TLS for pairing'
\`\`\`

## Task 3: Connect preparation to Application and QWebChannel

**Files:**

- Modify: \`/home/toberyan/orca/workspaces/uos-ai-0908/uos-ai-ios-tailscale-host/src/app/application.h\`
- Modify: \`/home/toberyan/orca/workspaces/uos-ai-0908/uos-ai-ios-tailscale-host/src/app/application.cpp\`
- Modify: \`/home/toberyan/orca/workspaces/uos-ai-0908/uos-ai-ios-tailscale-host/src/gui/web/remotecompanionchannel.h\`
- Modify: \`/home/toberyan/orca/workspaces/uos-ai-0908/uos-ai-ios-tailscale-host/src/gui/web/remotecompanionchannel.cpp\`
- Modify: \`/home/toberyan/orca/workspaces/uos-ai-0908/uos-ai-ios-tailscale-host/tests/test_remote_companion_server.cpp\`

**Interfaces:** Produces \`Application::prepareRemoteCompanion(const QStringList &workspaceIds)\` and QWebChannel \`prepare(const QString &workspaceIdsJson)\`. Both retain the existing \`configureRemoteCompanion\` result shape and v1 pairing behavior.

- [ ] **Step 1: Add failing WSS safety regression**

\`\`\`cpp
void RemoteCompanionServerTest::rejectsUnreadablePreparedTls()
{
    RemoteListenerConfig config {QHostAddress(QStringLiteral("100.64.0.2")), 45980, true,
                                 {QStringLiteral("uos-ai.example.ts.net"), "/missing/cert", "/missing/key"}};
    QVERIFY(!server.start(config));
    QCOMPARE(server.lastStartError(), QStringLiteral("TLS certificate or private key cannot be read"));
}
\`\`\`

Construct the fixture the same way as \`rejectsProductionListenerWithoutTls\`, without loopback test options.

- [ ] **Step 2: Verify test coverage**

\`\`\`sh
cmake --build /home/toberyan/orca/workspaces/uos-ai-0908/uos-ai-ios-tailscale-host/build-remote-tests --target test_remote_companion_server
ctest --test-dir /home/toberyan/orca/workspaces/uos-ai-0908/uos-ai-ios-tailscale-host/build-remote-tests -R '^test_remote_companion_server$' --output-on-failure
\`\`\`

Expected: the new test passes against the existing production TLS guard.

- [ ] **Step 3: Implement narrow prepare boundary**

\`\`\`cpp
QJsonObject Application::prepareRemoteCompanion(const QStringList &workspaceIds);
QString RemoteCompanionChannel::prepare(const QString &workspaceIdsJson) const;
\`\`\`

The channel accepts only a JSON array and uses the current normalized \`workspaceIds\` helper. Application rejects empty/unavailable workspace IDs before invoking \`m_remoteBootstrap->prepare(QStandardPaths::writableLocation(QStandardPaths::AppDataLocation))\`. It combines a successful identity/TLS result with saved-or-default port and delegates to:

\`\`\`cpp
configureRemoteCompanion(discoveredAddress, port, true, workspaceIds,
                         hostname, certificatePath, privateKeyPath)
\`\`\`

Initialize the bootstrap after app-data setup. Do not stop, overwrite, or persist prior configuration before \`configureRemoteCompanion\` succeeds. The channel must never accept an executable, argument, output path, or return raw command output.

- [ ] **Step 4: Verify host integration**

\`\`\`sh
cmake --build /home/toberyan/orca/workspaces/uos-ai-0908/uos-ai-ios-tailscale-host/build-remote-tests --target test_remote_companion_server test_tailscale_bootstrap
ctest --test-dir /home/toberyan/orca/workspaces/uos-ai-0908/uos-ai-ios-tailscale-host/build-remote-tests -R 'test_(remote_companion_server|tailscale_bootstrap)' --output-on-failure
\`\`\`

Expected: all bootstrap and production-WSS rejection tests pass.

- [ ] **Step 5: Commit**

\`\`\`sh
git -C /home/toberyan/orca/workspaces/uos-ai-0908/uos-ai-ios-tailscale-host add src/app/application.h src/app/application.cpp src/gui/web/remotecompanionchannel.h src/gui/web/remotecompanionchannel.cpp tests/test_remote_companion_server.cpp
git -C /home/toberyan/orca/workspaces/uos-ai-0908/uos-ai-ios-tailscale-host commit -m 'feat: prepare secure mobile companion listener'
\`\`\`

## Task 4: Test and replace desktop page with readable cards

**Files:**

- Create: \`/home/toberyan/orca/workspaces/uos-ai-0908/uos-ai-ios-tailscale-host/web/src/views/window/mainwindow/page/settings/remotecompanion/companionPresentation.ts\`
- Create: \`/home/toberyan/orca/workspaces/uos-ai-0908/uos-ai-ios-tailscale-host/web/tests/remotecompanion/companionPresentation.test.ts\`
- Modify: \`/home/toberyan/orca/workspaces/uos-ai-0908/uos-ai-ios-tailscale-host/web/src/views/window/mainwindow/page/settings/remotecompanion/RemoteCompanionPage.tsx\`
- Create: \`/home/toberyan/orca/workspaces/uos-ai-0908/uos-ai-ios-tailscale-host/web/src/assets/styles/window/mainwindow/page/settings/remotecompanion/RemoteCompanionPage.css\`
- Modify: \`/home/toberyan/orca/workspaces/uos-ai-0908/uos-ai-ios-tailscale-host/web/src/assets/styles/main.css\`
- Modify: \`/home/toberyan/orca/workspaces/uos-ai-0908/uos-ai-ios-tailscale-host/web/src/views/window/mainwindow/page/settings/remotecompanion/page.ts\`

**Interfaces:** \`presentationFor(status, workspaceIds)\` returns \`hostState\`, \`canPrepare\`, \`canShowInvitation\`, and \`workspaceHint\`.

- [ ] **Step 1: Write failing Node 24 test**

\`\`\`ts
import test from "node:test";
import assert from "node:assert/strict";
import { presentationFor } from "../../src/views/window/mainwindow/page/settings/remotecompanion/companionPresentation.ts";

test("workspace is required before preparation", () => {
    const page = presentationFor({ listening: false, tlsReady: false, listenerError: "" }, []);
    assert.equal(page.hostState, "not-ready");
    assert.equal(page.canPrepare, false);
});

test("QR needs ready WSS and a workspace", () => {
    const page = presentationFor({ listening: true, tlsReady: true, listenerError: "" }, ["workspace-a"]);
    assert.equal(page.hostState, "ready");
    assert.equal(page.canShowInvitation, true);
});
\`\`\`

- [ ] **Step 2: Verify RED**

\`\`\`sh
cd /home/toberyan/orca/workspaces/uos-ai-0908/uos-ai-ios-tailscale-host/web
source /home/toberyan/.config/nvm/nvm.sh && nvm use 24
node --experimental-strip-types --test tests/remotecompanion/companionPresentation.test.ts
\`\`\`

Expected: module-not-found because the helper is not present.

- [ ] **Step 3: Implement presentation state and themed view**

\`\`\`ts
export function presentationFor(status: Pick<RemoteStatus, "listening" | "tlsReady" | "listenerError">, workspaceIds: string[]) {
    const ready = status.listening && status.tlsReady;
    const selected = workspaceIds.length > 0;
    return {
        hostState: status.listenerError ? "error" : ready ? "ready" : "not-ready",
        canPrepare: selected && !ready,
        canShowInvitation: selected && ready,
        workspaceHint: selected ? "" : "Select at least one workspace to continue.",
    } as const;
}
\`\`\`

Replace inline layout with the MCP/Skills page shell. Render cards in this order: Secure host, Shared workspaces, Pair Android device, Advanced connection settings, Paired devices. The prepare button calls \`requestRemoteCompanion("prepare", JSON.stringify(sharedWorkspaceIds))\`; QR creation remains current \`createInvitation\`. Size QR at \`min(320px, 100%)\`, offer \`Copy link\`, and hide URI until \`Reveal recovery link\`. Use a semantic \`<details>\` for manual settings and \`backend.translate\` for all visible copy. CSS must define explicit \`html.dark\` tokens, a 1000px container, cards, focus rings, responsive spacing, and UOS font tokens. Rename navigation label to \`Mobile Companion\`.

- [ ] **Step 4: Verify GREEN and build**

\`\`\`sh
cd /home/toberyan/orca/workspaces/uos-ai-0908/uos-ai-ios-tailscale-host/web
source /home/toberyan/.config/nvm/nvm.sh && nvm use 24
node --experimental-strip-types --test tests/remotecompanion/companionPresentation.test.ts
npm run type-check
npm run build
\`\`\`

Expected: tests, TypeScript, and production build succeed.

- [ ] **Step 5: Commit**

\`\`\`sh
git -C /home/toberyan/orca/workspaces/uos-ai-0908/uos-ai-ios-tailscale-host add web/src/views/window/mainwindow/page/settings/remotecompanion/companionPresentation.ts web/tests/remotecompanion/companionPresentation.test.ts web/src/views/window/mainwindow/page/settings/remotecompanion/RemoteCompanionPage.tsx web/src/assets/styles/window/mainwindow/page/settings/remotecompanion/RemoteCompanionPage.css web/src/assets/styles/main.css web/src/views/window/mainwindow/page/settings/remotecompanion/page.ts
git -C /home/toberyan/orca/workspaces/uos-ai-0908/uos-ai-ios-tailscale-host commit -m 'feat: simplify mobile companion pairing UI'
\`\`\`

## Task 5: Test and make Android scanner-first

**Files:**

- Create: \`android/app/src/main/java/org/deepin/uosai/companion/feature/pairing/PairingEntryMode.kt\`
- Create: \`android/app/src/test/java/org/deepin/uosai/companion/feature/pairing/PairingEntryModeTest.kt\`
- Modify: \`android/app/src/main/java/org/deepin/uosai/companion/ui/CompanionApp.kt\`

**Interfaces:** \`PairingEntryMode.initial()\` returns \`Scanner\`; \`showManualEntry\` / \`showScanner\` change only local UI state. Existing callback and deep-link pairing behavior remain unchanged.

- [ ] **Step 1: Write failing test**

\`\`\`kotlin
class PairingEntryModeTest {
    @Test
    fun startsWithScannerAndRetainsManualRecovery() {
        assertEquals(PairingEntryMode.Scanner, PairingEntryMode.initial())
        assertEquals(PairingEntryMode.Manual, PairingEntryMode.Scanner.showManualEntry())
        assertEquals(PairingEntryMode.Scanner, PairingEntryMode.Manual.showScanner())
    }
}
\`\`\`

- [ ] **Step 2: Verify RED**

\`\`\`sh
android_jdk_home=/home/toberyan/.local/jdk-17/usr/lib/jvm/java-17-openjdk-amd64
JAVA_HOME="$android_jdk_home" PATH="$android_jdk_home/bin:$PATH" ANDROID_SDK_ROOT=/home/toberyan/.local/android-sdk ./android/gradlew --no-daemon :app:testDebugUnitTest --tests org.deepin.uosai.companion.feature.pairing.PairingEntryModeTest
\`\`\`

Expected: compilation fails because \`PairingEntryMode\` does not exist.

- [ ] **Step 3: Implement scanner-first entry**

\`\`\`kotlin
sealed interface PairingEntryMode {
    data object Scanner : PairingEntryMode
    data object Manual : PairingEntryMode
    companion object { fun initial(): PairingEntryMode = Scanner }
}
fun PairingEntryMode.showManualEntry() = PairingEntryMode.Manual
fun PairingEntryMode.showScanner() = PairingEntryMode.Scanner
\`\`\`

Start \`PairingScreen\` in Scanner mode. The compact card presents \`Scan UOS AI QR\` and \`Paste invitation instead\`; scanner mode hosts \`QrScannerDialog\`; manual mode retains the existing text field, Pair action, and \`Scan QR instead\`. Camera permission is requested only once scanner UI is visible. Preserve \`onPayload -> onPair(payload)\` and deep-link validation before any network use.

- [ ] **Step 4: Verify GREEN**

\`\`\`sh
android_jdk_home=/home/toberyan/.local/jdk-17/usr/lib/jvm/java-17-openjdk-amd64
JAVA_HOME="$android_jdk_home" PATH="$android_jdk_home/bin:$PATH" ANDROID_SDK_ROOT=/home/toberyan/.local/android-sdk ./android/gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug
\`\`\`

Expected: new test plus existing pairing, protocol, and reconnect tests pass; debug APK builds.

- [ ] **Step 5: Commit**

\`\`\`sh
git add android/app/src/main/java/org/deepin/uosai/companion/feature/pairing/PairingEntryMode.kt android/app/src/test/java/org/deepin/uosai/companion/feature/pairing/PairingEntryModeTest.kt android/app/src/main/java/org/deepin/uosai/companion/ui/CompanionApp.kt
git commit -m 'feat: make Android companion pairing scan-first'
\`\`\`

## Task 6: Documentation and integrated validation

**Files:**

- Modify: \`README.md\`
- Modify: \`docs/operations/tailscale-companion.md\`

- [ ] **Step 1: Verify the docs lack the new labels**

\`\`\`sh
rg -n 'Prepare secure pairing|Show pairing QR|Scan UOS AI QR|Advanced connection settings' README.md docs/operations/tailscale-companion.md
\`\`\`

Expected: no matches before documentation edit.

- [ ] **Step 2: Document the normal and recovery flows**

Add exactly this workflow:

\`\`\`markdown
1. Select at least one workspace in **Mobile Companion**.
2. Press **Prepare secure pairing**. UOS AI reads local Tailscale state and, only for this request, asks Tailscale to write host TLS files under UOS AI application data.
3. When Secure host shows Ready, press **Show pairing QR**.
4. On Android choose **Scan UOS AI QR**. Use **Paste invitation instead** only when camera scanning is unavailable.
\`\`\`

State that preparation is user initiated, QR requires a selected workspace and ready WSS listener, key files stay in app data, Advanced connection settings is the manual fallback, and there is no \`ws://\` fallback.

- [ ] **Step 3: Run complete checks and install Android build**

\`\`\`sh
cmake --build /home/toberyan/orca/workspaces/uos-ai-0908/uos-ai-ios-tailscale-host/build-remote-tests
ctest --test-dir /home/toberyan/orca/workspaces/uos-ai-0908/uos-ai-ios-tailscale-host/build-remote-tests --output-on-failure
cd /home/toberyan/orca/workspaces/uos-ai-0908/uos-ai-ios-tailscale-host/web && source /home/toberyan/.config/nvm/nvm.sh && nvm use 24 && npm run type-check && npm run build
adb -s 192.168.0.134:34525 install -r /home/toberyan/orca/workspaces/deepin-ai-for-ios/barramundi/android/app/build/outputs/apk/debug/app-debug.apk
adb -s 192.168.0.134:34525 shell am start -W -n org.deepin.uosai.companion/.MainActivity
\`\`\`

Expected: host tests, frontend checks, Android installation, and cold launch all pass. Then send an expired \`uos-ai://pair\` deep link and confirm Android presents expiry before any socket opens. Real workspace/Agent verification requires a fresh QR from the prepared UOS host.

- [ ] **Step 4: Commit and inspect worktrees**

\`\`\`sh
git add README.md docs/operations/tailscale-companion.md
git commit -m 'docs: explain scan-first mobile pairing'
git diff --check && git status --short
git -C /home/toberyan/orca/workspaces/uos-ai-0908/uos-ai-ios-tailscale-host status --short
\`\`\`

Expected: this companion worktree is clean; the host's known user-owned changes remain unmodified.
