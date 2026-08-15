"use strict";

const fs = require("fs");
const yauzl = require("yauzl");

const ARTIFACTS = {
    "linux-x64": {artifact: "optraix-linux-x86_64", executable: "optraix"},
    "win32-x64": {artifact: "optraix-windows-x86_64", executable: "optraix.exe"},
};

function artifactForPlatform(platform, architecture) {
    return ARTIFACTS[`${platform}-${architecture}`];
}

function latestSuccessfulRun(payload) {
    return (payload.workflow_runs ?? []).find((run) =>
        run.status === "completed" && run.conclusion === "success" && run.head_branch === "main",
    );
}

function matchingArtifact(payload, name) {
    return (payload.artifacts ?? []).find((artifact) => artifact.name === name && !artifact.expired);
}

function extractExecutable(archive, expectedName, destination) {
    return new Promise((resolve, reject) => {
        yauzl.open(archive, {lazyEntries: true, validateFileName: true}, (openError, zip) => {
            if (openError) {
                reject(openError);
                return;
            }

            let extracted = false;
            let settled = false;
            const fail = (error) => {
                if (settled) return;
                settled = true;
                zip.close();
                reject(error);
            };
            const succeed = () => {
                if (settled) return;
                settled = true;
                zip.close();
                resolve();
            };

            zip.on("error", fail);
            zip.on("entry", (entry) => {
                if (entry.fileName !== expectedName || /\/$/.test(entry.fileName)) {
                    zip.readEntry();
                    return;
                }
                if (extracted) {
                    fail(new Error(`archive contains more than one ${expectedName} executable`));
                    return;
                }
                extracted = true;
                zip.openReadStream(entry, (streamError, stream) => {
                    if (streamError) {
                        fail(streamError);
                        return;
                    }
                    const file = fs.createWriteStream(destination, {flags: "wx"});
                    stream.on("error", fail);
                    file.on("error", fail);
                    file.on("close", succeed);
                    stream.pipe(file);
                });
            });
            zip.on("end", () => {
                if (!extracted) fail(new Error(`archive has no ${expectedName} executable`));
            });
            zip.readEntry();
        });
    });
}

module.exports = {artifactForPlatform, extractExecutable, latestSuccessfulRun, matchingArtifact};
