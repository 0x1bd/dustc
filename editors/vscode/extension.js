"use strict";

const vscode = require("vscode");
const fs = require("fs");
const https = require("https");
const os = require("os");
const path = require("path");
const {spawn} = require("child_process");

const ASSETS = {
    "linux-x64": "dustc-linux-x86_64",
    "linux-arm64": "dustc-linux-aarch64",
    "darwin-arm64": "dustc-macos-aarch64",
    "win32-x64": "dustc-windows-x86_64.exe",
};

const INSTALLED_TAG = "dust.installedTag";

let output;
let diagnostics;

function activate(context) {
    output = vscode.window.createOutputChannel("Dust");
    diagnostics = vscode.languages.createDiagnosticCollection("dust");
    context.subscriptions.push(output, diagnostics);

    context.subscriptions.push(
        vscode.commands.registerCommand("dust.build", () => build(context)),
        vscode.commands.registerCommand("dust.downloadCompiler", () => downloadCompiler(context, true)),
        vscode.commands.registerCommand("dust.showCompilerPath", () => showCompilerPath(context)),
    );
}

function deactivate() {
}

function settings() {
    return vscode.workspace.getConfiguration("dust");
}

function managedPath(context) {
    const name = process.platform === "win32" ? "dustc.exe" : "dustc";
    return path.join(context.globalStorageUri.fsPath, "bin", name);
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
        const expanded = configured.startsWith("~")
            ? path.join(os.homedir(), configured.slice(1))
            : configured;
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

async function fetchToFile(url, destination, token, onProgress) {
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
    await fs.promises.chmod(destination, 0o755);
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
        return;
    }

    output.show(true);
    const failure = result.stderr.trim();
    if (!failure.startsWith("error:")) {
        await vscode.window.showErrorMessage(failure.split("\n")[0] || `dustc exited with code ${result.code}`);
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
