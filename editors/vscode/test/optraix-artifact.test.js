"use strict";

const assert = require("assert");
const fs = require("fs");
const os = require("os");
const path = require("path");
const test = require("node:test");
const {artifactForPlatform, extractExecutable, latestSuccessfulRun, matchingArtifact} = require("../optraix-artifact");

const FIXTURE_ZIP = "UEsDBAoAAAAAACeWD10FsmSbEAAAABAAAAAHABwAb3B0cmFpeFVUCQADiZiAaomYgGp1eAsAAQToAwAABOgDAABvcHRyYWl4IGZpeHR1cmUKUEsBAh4DCgAAAAAAJ5YPXQWyZJsQAAAAEAAAAAcAGAAAAAAAAQAAAKSBAAAAAG9wdHJhaXhVVAUAA4mYgGp1eAsAAQToAwAABOgDAABQSwUGAAAAAAEAAQBNAAAAUQAAAAAA";

test("selects only supported optraIX platforms", () => {
    assert.deepStrictEqual(artifactForPlatform("linux", "x64"), {
        artifact: "optraix-linux-x86_64",
        executable: "optraix"
    });
    assert.deepStrictEqual(artifactForPlatform("win32", "x64"), {
        artifact: "optraix-windows-x86_64",
        executable: "optraix.exe"
    });
    assert.strictEqual(artifactForPlatform("darwin", "arm64"), undefined);
});

test("selects a successful main run and a live matching artifact", () => {
    const run = {id: 42, status: "completed", conclusion: "success", head_branch: "main"};
    assert.strictEqual(latestSuccessfulRun({
        workflow_runs: [{
            status: "completed",
            conclusion: "failure",
            head_branch: "main"
        }, run]
    }), run);
    const artifact = {name: "optraix-linux-x86_64", expired: false};
    assert.strictEqual(matchingArtifact({
        artifacts: [{
            ...artifact,
            expired: true
        }, artifact]
    }, artifact.name), artifact);
});

test("extracts only the expected executable", async () => {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), "optraix-artifact-"));
    const archive = path.join(directory, "artifact.zip");
    const destination = path.join(directory, "optraix");
    fs.writeFileSync(archive, Buffer.from(FIXTURE_ZIP, "base64"));

    await extractExecutable(archive, "optraix", destination);
    assert.strictEqual(fs.readFileSync(destination, "utf8"), "optraix fixture\n");
    await assert.rejects(extractExecutable(archive, "unexpected", path.join(directory, "unexpected")), /no unexpected executable/);
    fs.rmSync(directory, {recursive: true, force: true});
});
