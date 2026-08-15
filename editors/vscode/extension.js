"use strict";

const vscode = require("vscode");
const fs = require("fs");
const https = require("https");
const os = require("os");
const path = require("path");
const {spawn} = require("child_process");
const {artifactForPlatform, extractExecutable, latestSuccessfulRun, matchingArtifact} = require("./optraix-artifact");

const ASSETS = {
    "linux-x64": "dustc-linux-x86_64",
    "linux-arm64": "dustc-linux-aarch64",
    "darwin-arm64": "dustc-macos-aarch64",
    "win32-x64": "dustc-windows-x86_64.exe",
};

const INSTALLED_TAG = "dust.installedTag";
const OPTRAIX_INSTALLED_ARTIFACT = "dust.optraixInstalledArtifact";
const OPTRAIX_REPOSITORY = "0x1bd/optraix";

let output;
let diagnostics;
let optraixOutput;
let optraixProcess;

function activate(context) {
    output = vscode.window.createOutputChannel("Dust");
    optraixOutput = vscode.window.createOutputChannel("optraIX");
    diagnostics = vscode.languages.createDiagnosticCollection("dust");
    context.subscriptions.push(output, optraixOutput, diagnostics);

    context.subscriptions.push(
        vscode.commands.registerCommand("dust.build", () => build(context)),
        vscode.commands.registerCommand("dust.downloadCompiler", () => downloadCompiler(context, true)),
        vscode.commands.registerCommand("dust.showCompilerPath", () => showCompilerPath(context)),
        vscode.commands.registerCommand("dust.downloadOptraix", () => downloadOptraix(context, true)),
        vscode.commands.registerCommand("dust.runOptraix", () => runOptraix(context)),
        vscode.commands.registerCommand("dust.stopOptraix", () => stopOptraix(true)),
    );
}

function deactivate() {
    stopOptraix(false);
}

function settings() {
    return vscode.workspace.getConfiguration("dust");
}

function managedPath(context) {
    const name = process.platform === "win32" ? "dustc.exe" : "dustc";
    return path.join(context.globalStorageUri.fsPath, "bin", name);
}

function managedOptraixPath(context) {
    const platform = artifactForPlatform(process.platform, process.arch);
    return path.join(context.globalStorageUri.fsPath, "optraix", "bin", platform?.executable ?? "optraix");
}

function optraixRunDirectory(context) {
    const configured = settings().get("optraixRunDirectory", "").trim();
    if (!configured) return path.join(context.globalStorageUri.fsPath, "optraix", "run");

    const expanded = expandHome(configured);
    if (path.isAbsolute(expanded)) return expanded;
    const workspace = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
    return path.resolve(workspace ?? context.globalStorageUri.fsPath, expanded);
}

function expandHome(candidate) {
    return candidate.startsWith("~") ? path.join(os.homedir(), candidate.slice(1)) : candidate;
}

function isExecutableFile(candidate) {
    try {
        if (!fs.statSync(candidate).isFile()) return false;
        if (process.platform !== "win32") fs.accessSync(candidate, fs.constants.X_OK);
        return true;
    } catch {
        return false;
    }
}

function findCompiler(context) {
    const configured = settings().get("compilerPath", "").trim();
    if (configured) {
        const expanded = expandHome(configured);
        return isExecutableFile(expanded) ? expanded : undefined;
    }

    const managed = managedPath(context);
    if (isExecutableFile(managed)) return managed;

    const name = process.platform === "win32" ? "dustc.exe" : "dustc";
    for (const folder of vscode.workspace.workspaceFolders ?? []) {
        const local = path.join(folder.uri.fsPath, name);
        if (isExecutableFile(local)) return local;
    }

    return onPath(name);
}

function onPath(name) {
    const extensions = process.platform === "win32" ? (process.env.PATHEXT ?? ".EXE").split(";") : [""];
    for (const entry of (process.env.PATH ?? "").split(path.delimiter)) {
        if (!entry) continue;
        for (const extension of extensions) {
            const candidate = path.join(entry, name.endsWith(extension) ? name : name + extension);
            if (isExecutableFile(candidate)) return candidate;
        }
    }
    return undefined;
}

async function requireCompiler(context) {
    const found = findCompiler(context);
    if (found) return found;

    const configured = settings().get("compilerPath", "").trim();
    if (configured) {
        await vscode.window.showErrorMessage(
            `dust.compilerPath points at "${configured}", which is not an executable file.`,
        );
        return undefined;
    }

    const choice = await vscode.window.showWarningMessage(
        "dustc was not found. Download the latest release?",
        "Download",
        "Cancel",
    );
    if (choice !== "Download") return undefined;
    return downloadCompiler(context, false);
}

async function showCompilerPath(context) {
    const found = findCompiler(context);
    if (!found) {
        await vscode.window.showWarningMessage("dustc was not found. Run “Dust: Download Latest dustc”.");
        return;
    }
    const stamp = context.globalState.get(INSTALLED_TAG);
    const tag = typeof stamp === "string" ? stamp.split("@")[0] : undefined;
    const suffix = found === managedPath(context) && tag ? ` (${tag})` : "";
    await vscode.window.showInformationMessage(`dustc: ${found}${suffix}`);
}

async function downloadCompiler(context, interactive) {
    const target = `${process.platform}-${process.arch}`;
    const asset = ASSETS[target];
    if (!asset) {
        await vscode.window.showErrorMessage(
            `No prebuilt dustc for ${target}. Build one with "./gradlew nativeCompile" and set "dust.compilerPath".`,
        );
        return undefined;
    }

    const repository = settings().get("compilerRepository", "0x1bd/dustc").trim();
    const channel = settings().get("updateChannel", "stable");
    const destination = managedPath(context);

    try {
        return await vscode.window.withProgress(
            {location: vscode.ProgressLocation.Notification, title: "Downloading dustc", cancellable: true},
            async (progress, token) => {
                progress.report({message: `looking up ${channel} release`});
                const release = await latestRelease(repository, asset, channel, token);
                const download = release.assets.find((candidate) => candidate.name === asset);
                if (!download) {
                    throw new Error(`release ${release.tag_name} has no ${asset} asset`);
                }

                const stamp = `${release.tag_name}@${download.id}`;
                if (stamp === context.globalState.get(INSTALLED_TAG) && isExecutableFile(destination)) {
                    if (interactive) {
                        void vscode.window.showInformationMessage(`dustc ${release.tag_name} is already installed.`);
                    }
                    return destination;
                }

                progress.report({message: `${release.tag_name} (${asset})`});
                await fetchToFile(download.browser_download_url, destination, token, (fraction) =>
                    progress.report({message: `${release.tag_name} — ${Math.round(fraction * 100)}%`}),
                );
                await context.globalState.update(INSTALLED_TAG, stamp);

                if (interactive) {
                    void vscode.window.showInformationMessage(`Installed dustc ${release.tag_name}.`);
                }
                return destination;
            },
        );
    } catch (error) {
        if (error instanceof vscode.CancellationError) return undefined;
        await vscode.window.showErrorMessage(`Could not download dustc: ${describe(error)}`);
        return undefined;
    }
}

async function latestRelease(repository, asset, channel, token) {
    const base = `https://api.github.com/repos/${repository}/releases`;
    const carriesAsset = (release) => (release.assets ?? []).some((candidate) => candidate.name === asset);

    if (channel !== "nightly") {
        try {
            const release = JSON.parse(await fetchText(`${base}/latest`, token));
            if (carriesAsset(release)) return release;
        } catch (error) {
            if (error instanceof vscode.CancellationError) throw error;
        }
    }

    const releases = JSON.parse(await fetchText(`${base}?per_page=20`, token));
    const match = releases.find(carriesAsset);
    if (!match) throw new Error(`no release of ${repository} publishes ${asset}`);
    return match;
}

async function downloadOptraix(context, interactive) {
    const platform = artifactForPlatform(process.platform, process.arch);
    if (!platform) {
        await vscode.window.showErrorMessage(
            `No prebuilt optraIX CI artifact is available for ${process.platform}-${process.arch}.`,
        );
        return undefined;
    }

    const destination = managedOptraixPath(context);
    try {
        return await vscode.window.withProgress(
            {location: vscode.ProgressLocation.Notification, title: "Downloading optraIX", cancellable: true},
            async (progress, token) => {
                progress.report({message: "looking up latest successful main build"});
                const artifact = await latestOptraixArtifact(platform.artifact, token);
                const stamp = `${artifact.run.id}@${artifact.id}@${artifact.run.head_sha}`;
                if (stamp === context.globalState.get(OPTRAIX_INSTALLED_ARTIFACT) && isExecutableFile(destination)) {
                    if (interactive) void vscode.window.showInformationMessage("The latest optraIX is already installed.");
                    return destination;
                }

                progress.report({message: `${artifact.run.head_sha.slice(0, 7)} (${platform.artifact})`});
                await installOptraixArtifact(artifact, destination, platform.executable, token, (fraction) =>
                    progress.report({message: `${Math.round(fraction * 100)}%`}),
                );
                await context.globalState.update(OPTRAIX_INSTALLED_ARTIFACT, stamp);
                if (interactive) {
                    void vscode.window.showInformationMessage(`Installed optraIX ${artifact.run.head_sha.slice(0, 7)}.`);
                }
                return destination;
            },
        );
    } catch (error) {
        if (error instanceof vscode.CancellationError) return undefined;
        await vscode.window.showErrorMessage(`Could not download optraIX: ${describe(error)}`);
        return undefined;
    }
}

async function latestOptraixArtifact(name, token) {
    const base = `https://api.github.com/repos/${OPTRAIX_REPOSITORY}/actions`;
    const runs = JSON.parse(await fetchText(`${base}/workflows/build.yml/runs?branch=main&status=success&per_page=20`, token));
    const run = latestSuccessfulRun(runs);
    if (!run) throw new Error(`no successful main build found in ${OPTRAIX_REPOSITORY}`);

    const artifacts = JSON.parse(await fetchText(`${base}/runs/${run.id}/artifacts?name=${encodeURIComponent(name)}&per_page=100`, token));
    const artifact = matchingArtifact(artifacts, name);
    if (!artifact) throw new Error(`build ${run.id} has no live ${name} artifact`);
    return {...artifact, run};
}

async function installOptraixArtifact(artifact, destination, executable, token, onProgress) {
    await fs.promises.mkdir(path.dirname(destination), {recursive: true});
    const archive = `${destination}.zip`;
    const candidate = `${destination}.candidate`;
    try {
        await fetchToFile(artifact.archive_download_url, archive, token, onProgress, false);
        await fs.promises.rm(candidate, {force: true});
        await extractExecutable(archive, executable, candidate);
        if (process.platform !== "win32") await fs.promises.chmod(candidate, 0o755);
        await fs.promises.rm(destination, {force: true});
        await fs.promises.rename(candidate, destination);
    } finally {
        await fs.promises.rm(archive, {force: true});
        await fs.promises.rm(candidate, {force: true});
    }
}

async function runOptraix(context) {
    if (optraixProcess) {
        await vscode.window.showInformationMessage("optraIX is already running.");
        return;
    }

    let executable = managedOptraixPath(context);
    if (!isExecutableFile(executable)) executable = await downloadOptraix(context, false);
    if (!executable) return;

    const runDirectory = optraixRunDirectory(context);
    const extra = settings().get("optraixArguments", []);
    await fs.promises.mkdir(runDirectory, {recursive: true});

    optraixOutput.clear();
    optraixOutput.appendLine(`> ${executable} ${[...extra, "--run-dir", runDirectory].join(" ")}`);
    const child = spawn(executable, [...extra, "--run-dir", runDirectory], {cwd: runDirectory});
    optraixProcess = child;
    child.stdout.on("data", (chunk) => optraixOutput.append(chunk.toString()));
    child.stderr.on("data", (chunk) => optraixOutput.append(chunk.toString()));
    child.on("error", async (error) => {
        if (optraixProcess === child) optraixProcess = undefined;
        optraixOutput.appendLine(describe(error));
        optraixOutput.show(true);
        await vscode.window.showErrorMessage(`Could not run optraIX: ${describe(error)}`);
    });
    child.on("close", (code, signal) => {
        if (optraixProcess === child) optraixProcess = undefined;
        optraixOutput.appendLine(`optraIX exited (${signal ?? code ?? "unknown"}).`);
    });
    optraixOutput.show(true);
    await vscode.window.showInformationMessage(`optraIX started in ${runDirectory}.`);
}

async function stopOptraix(interactive) {
    const child = optraixProcess;
    if (!child) {
        if (interactive) await vscode.window.showInformationMessage("optraIX is not running.");
        return;
    }
    if (process.platform === "win32" && interactive) {
        await vscode.window.showWarningMessage("Stopping optraIX on Windows may not save the world before termination.");
    }
    child.kill(process.platform === "win32" ? undefined : "SIGTERM");
}

const USER_AGENT = "dust-vscode";

function request(url, token, headers, onResponse) {
    return new Promise((resolve, reject) => {
        const attempt = (target, redirects) => {
            const pending = https.get(
                target,
                {headers: {"User-Agent": USER_AGENT, ...headers}},
                (response) => {
                    const status = response.statusCode ?? 0;
                    const location = response.headers.location;
                    if (status >= 300 && status < 400 && location) {
                        response.resume();
                        if (redirects === 0) {
                            reject(new Error("too many redirects"));
                            return;
                        }
                        attempt(new URL(location, target).toString(), redirects - 1);
                        return;
                    }
                    if (status !== 200) {
                        response.resume();
                        reject(new Error(`HTTP ${status} for ${target}`));
                        return;
                    }
                    onResponse(response, resolve, reject);
                },
            );
            pending.on("error", reject);
            const cancel = token?.onCancellationRequested(() => {
                pending.destroy();
                reject(new vscode.CancellationError());
            });
            pending.on("close", () => cancel?.dispose());
        };
        attempt(url, 5);
    });
}

function fetchText(url, token) {
    return request(url, token, {Accept: "application/vnd.github+json"}, (response, resolve, reject) => {
        const chunks = [];
        response.on("data", (chunk) => chunks.push(chunk));
        response.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
        response.on("error", reject);
    });
}

async function fetchToFile(url, destination, token, onProgress, executable = true) {
    await fs.promises.mkdir(path.dirname(destination), {recursive: true});
    const partial = `${destination}.partial`;

    await request(url, token, {Accept: "application/octet-stream"}, (response, resolve, reject) => {
        const total = Number(response.headers["content-length"] ?? 0);
        let received = 0;
        const file = fs.createWriteStream(partial);
        response.on("data", (chunk) => {
            received += chunk.length;
            if (total > 0) onProgress(received / total);
        });
        response.pipe(file);
        file.on("finish", () => file.close((error) => (error ? reject(error) : resolve(undefined))));
        file.on("error", reject);
        response.on("error", reject);
    }).catch(async (error) => {
        await fs.promises.rm(partial, {force: true});
        throw error;
    });

    await fs.promises.rm(destination, {force: true});
    await fs.promises.rename(partial, destination);
    if (executable && process.platform !== "win32") await fs.promises.chmod(destination, 0o755);
}

async function build(context) {
    const editor = vscode.window.activeTextEditor;
    if (!editor || editor.document.languageId !== "dust") {
        await vscode.window.showWarningMessage("Open a .dust file to build it.");
        return;
    }
    if (editor.document.isUntitled) {
        await vscode.window.showWarningMessage("Save the file before building it.");
        return;
    }
    await editor.document.save();

    const compiler = await requireCompiler(context);
    if (!compiler) return;

    const source = editor.document.uri.fsPath;
    const schematic = path.join(path.dirname(source), `${path.basename(source, ".dust")}.schem`);
    const extra = settings().get("buildArguments", []);
    const cwd = vscode.workspace.getWorkspaceFolder(editor.document.uri)?.uri.fsPath ?? path.dirname(source);

    output.clear();
    output.appendLine(`> ${compiler} build ${source} -o ${schematic} ${extra.join(" ")}`.trimEnd());

    let progressBuffer = "";
    const result = await vscode.window.withProgress(
        {location: vscode.ProgressLocation.Notification, title: `Building ${path.basename(source)}`, cancellable: false},
        async (progress) => run(
            compiler,
            ["build", source, "-o", schematic, ...extra],
            cwd,
            (chunk) => {
                output.append(chunk);
                progressBuffer += chunk;
                const lines = progressBuffer.split(/\r?\n/);
                progressBuffer = lines.pop() ?? "";
                for (const line of lines) {
                    const stage = /^dustc: (synthesis|placement|routing|electrical finalization|emission)$/.exec(line.trim());
                    if (stage) progress.report({message: stage[1]});
                }
            },
            (chunk) => output.append(chunk),
        ),
    );

    diagnostics.clear();
    for (const [file, entries] of parseDiagnostics(result.stderr, cwd)) {
        diagnostics.set(vscode.Uri.file(file), entries);
    }

    if (result.code === 0) {
        const summary = result.stdout.trim().split("\n").pop() ?? `wrote ${schematic}`;
        await vscode.window.showInformationMessage(summary.replace(/^dustc: /, ""));
        await copySchematicToOptraix(context, schematic);
        return;
    }

    output.show(true);
    const failure = result.stderr.trim();
    if (!failure.startsWith("error:")) {
        await vscode.window.showErrorMessage(failure.split("\n")[0] || `dustc exited with code ${result.code}`);
    }
}

async function copySchematicToOptraix(context, schematic) {
    const directory = path.join(optraixRunDirectory(context), "schematics");
    const destination = path.join(directory, path.basename(schematic));
    try {
        await fs.promises.mkdir(directory, {recursive: true});
        await fs.promises.copyFile(schematic, destination);
        output.appendLine(`copied ${schematic} to ${destination}`);
    } catch (error) {
        output.appendLine(`could not copy ${schematic} to optraIX: ${describe(error)}`);
        await vscode.window.showErrorMessage(`Built schematic but could not copy it to optraIX: ${describe(error)}`);
    }
}

function run(command, args, cwd, onStdout, onStderr) {
    return new Promise((resolve) => {
        const child = spawn(command, args, {cwd});
        let stdout = "";
        let stderr = "";
        child.stdout.on("data", (chunk) => {
            const text = chunk.toString();
            stdout += text;
            onStdout?.(text);
        });
        child.stderr.on("data", (chunk) => {
            const text = chunk.toString();
            stderr += text;
            onStderr?.(text);
        });
        child.on("error", (error) => {
            const text = `${describe(error)}\n`;
            stderr += text;
            onStderr?.(text);
            resolve({code: -1, stdout, stderr});
        });
        child.on("close", (code) => resolve({code: code ?? -1, stdout, stderr}));
    });
}

function parseDiagnostics(text, cwd) {
    const lines = text.split(/\r?\n/);
    const byFile = new Map();

    for (let index = 0; index < lines.length; index++) {
        const header = /^error: (.*)$/.exec(lines[index]);
        if (!header) continue;

        let location;
        let length = 1;
        for (let ahead = index + 1; ahead < lines.length && ahead <= index + 6; ahead++) {
            if (/^error: /.test(lines[ahead])) break;
            location ??= /^\s*-->\s*(.+):(\d+):(\d+)\s*$/.exec(lines[ahead]) ?? undefined;
            const carets = /^\s*\|\s*(\^+)\s*$/.exec(lines[ahead]);
            if (carets) {
                length = carets[1].length;
                break;
            }
        }
        if (!location) continue;

        const [, file, line, column] = location;
        const start = new vscode.Position(Number(line) - 1, Number(column) - 1);
        const diagnostic = new vscode.Diagnostic(
            new vscode.Range(start, start.translate(0, length)),
            header[1],
            vscode.DiagnosticSeverity.Error,
        );
        diagnostic.source = "dustc";

        const resolved = path.isAbsolute(file) ? file : path.join(cwd, file);
        byFile.set(resolved, [...(byFile.get(resolved) ?? []), diagnostic]);
    }

    return byFile;
}

function describe(error) {
    return error instanceof Error ? error.message : String(error);
}

module.exports = {activate, deactivate};
