//! Post-build APK compression: rewrite classes*.dex entries from STORED to
//! DEFLATED, then re-align and re-sign the APK with the same keystore used by
//! the Gradle `signingConfig`.
//!
//! AGP stores the merged DEX files in the APK without compression. Since DEX
//! is the dominant part of the archive (typically 70%+ of the APK size),
//! switching those entries to DEFLATE shrinks the distributed APK without
//! touching any code semantics.

use anyhow::{Context, Result, bail};
use clap::Args;
use std::{
    env, fs,
    io::{BufWriter, Read, Write},
    path::{Path, PathBuf},
    process::Command,
};
use zip::{CompressionMethod, ZipArchive, ZipWriter, write::SimpleFileOptions};

#[derive(Args)]
pub(crate) struct ApkRecompressArgs {
    /// Signed APK produced by `assembleRelease`, recompressed and re-signed in place.
    #[arg(long)]
    pub apk: PathBuf,

    /// Explicit `zipalign` binary. Defaults to the newest `$ANDROID_HOME/build-tools/*`.
    #[arg(long)]
    pub zipalign: Option<PathBuf>,

    /// Explicit `apksigner` binary. Defaults to the newest `$ANDROID_HOME/build-tools/*`.
    #[arg(long)]
    pub apksigner: Option<PathBuf>,
}

/// Entries whose name should be recompressed to DEFLATE.
fn should_deflate(name: &str) -> bool {
    name == "classes.dex" || (name.starts_with("classes") && name.ends_with(".dex"))
}

pub(crate) fn run(args: &ApkRecompressArgs) -> Result<()> {
    let apk = &args.apk;
    if !apk.exists() {
        bail!("APK {} does not exist", apk.display());
    }

    let work_dir = apk
        .parent()
        .with_context(|| format!("{} has no parent directory", apk.display()))?
        .to_path_buf();
    let file_name = apk
        .file_name()
        .with_context(|| format!("{} has no file name", apk.display()))?
        .to_string_lossy()
        .into_owned();

    let recompressed = work_dir.join(format!("{file_name}.recompressed"));
    let aligned = work_dir.join(format!("{file_name}.aligned"));
    let signed = work_dir.join(format!("{file_name}.signed"));

    recompress_dex(apk, &recompressed)?;

    let zipalign = resolve_build_tool(args.zipalign.as_deref(), "zipalign", &work_dir)?;
    run_tool(
        &zipalign,
        &["-f", "4", recompressed.to_str().unwrap(), aligned.to_str().unwrap()],
    )?;

    let apksigner = resolve_build_tool(args.apksigner.as_deref(), "apksigner", &work_dir)?;
    sign_apk(&apksigner, &aligned, &signed)?;

    fs::rename(&signed, apk).with_context(|| {
        format!(
            "could not replace {} with the re-signed APK",
            apk.display()
        )
    })?;

    for interim in [&recompressed, &aligned] {
        let _ = fs::remove_file(interim);
    }

    Ok(())
}

fn recompress_dex(input: &Path, output: &Path) -> Result<()> {
    let mut archive = ZipArchive::new(fs::File::open(input).with_context(|| {
        format!("could not open APK {}", input.display())
    })?)
    .with_context(|| format!("could not read APK {}", input.display()))?;

    let out_file = fs::File::create(output).with_context(|| {
        format!("could not create {}", output.display())
    })?;
    let mut writer = ZipWriter::new(BufWriter::new(out_file));

    for index in 0..archive.len() {
        let mut entry = archive
            .by_index(index)
            .with_context(|| format!("could not read APK entry #{index}"))?;
        let name = entry.name().to_owned();
        let method = entry.compression();
        let mut bytes = Vec::with_capacity(entry.size() as usize);
        entry.read_to_end(&mut bytes)?;

        // Only DEX entries switch to DEFLATE; every other file keeps its
        // original method (Deflated files stay deflated, Stored stay stored).
        let options = if should_deflate(&name) {
            SimpleFileOptions::default()
                .compression_method(CompressionMethod::Deflated)
                .compression_level(Some(9))
        } else {
            SimpleFileOptions::default().compression_method(method)
        };
        writer.start_file(&name, options)?;
        writer.write_all(&bytes)?;
    }

    writer.finish()?.flush()?;
    Ok(())
}

fn sign_apk(apksigner_bin: &Path, input: &Path, output: &Path) -> Result<()> {
    let keystore = env::var("WEKIT_KEYSTORE_FILE")
        .with_context(|| "WEKIT_KEYSTORE_FILE is not set (needed to re-sign the APK)")?;
    let store_password = env::var("WEKIT_KEYSTORE_PASSWORD")
        .with_context(|| "WEKIT_KEYSTORE_PASSWORD is not set")?;
    let key_alias =
        env::var("WEKIT_KEY_ALIAS").with_context(|| "WEKIT_KEY_ALIAS is not set")?;
    let key_password = env::var("WEKIT_KEY_PASSWORD")
        .with_context(|| "WEKIT_KEY_PASSWORD is not set")?;

    run_tool(
        apksigner_bin,
        &[
            "sign",
            "--ks",
            &keystore,
            "--ks-key-alias",
            &key_alias,
            "--ks-pass",
            &format!("pass:{store_password}"),
            "--key-pass",
            &format!("pass:{key_password}"),
            // Match the Gradle signing config: no V1, V2/V3 verification sets.
            "--v1-signing-enabled",
            "false",
            "--v2-signing-enabled",
            "true",
            "--v3-signing-enabled",
            "true",
            // V4 is only used for incremental installs and would need an extra
            // .idsig artifact; skip it for the distributed APK.
            "--v4-signing-enabled",
            "false",
            "--out",
            output.to_str().unwrap(),
            input.to_str().unwrap(),
        ],
    )
}

fn resolve_build_tool(
    explicit: Option<&Path>,
    file_name: &str,
    work_dir: &Path,
) -> Result<PathBuf> {
    if let Some(path) = explicit {
        if path.exists() {
            return Ok(path.to_path_buf());
        }
        bail!("{file_name} binary {} does not exist", path.display());
    }

    if let Ok(home) = env::var("ANDROID_HOME") {
        let build_tools = Path::new(&home).join("build-tools");
        if let Ok(entries) = fs::read_dir(&build_tools) {
            let mut versions: Vec<PathBuf> = entries
                .filter_map(|e| e.ok().map(|e| e.path()))
                .filter(|p| p.is_dir())
                .collect();
            versions.sort_by(|a, b| cmp_version_path(a, b));
            if let Some(dir) = versions.last() {
                let candidate = dir.join(file_name);
                if candidate.exists() {
                    return Ok(candidate);
                }
            }
        }
    }

    bail!("could not find {file_name} in $ANDROID_HOME/build-tools, and none was given (cwd: {})",
        work_dir.display()
    );
}

/// Compare two `.../build-tools/<version>` dirs numerically (36.0.0 > 34.0.0).
fn cmp_version_path(a: &Path, b: &Path) -> std::cmp::Ordering {
    fn parts(path: &Path) -> Vec<u64> {
        path.components()
            .filter_map(|c| c.as_os_str().to_str())
            .filter_map(|s| s.split('.').next())
            .filter_map(|s| s.parse::<u64>().ok())
            .collect()
    }
    parts(a).cmp(&parts(b))
}

fn run_tool(binary: &Path, args: &[&str]) -> Result<()> {
    let status = Command::new(binary)
        .args(args)
        .status()
        .with_context(|| format!("could not execute {}", binary.display()))?;
    if !status.success() {
        bail!("{} failed with {status}", binary.display());
    }
    Ok(())
}