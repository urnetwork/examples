//! Build script: creates placeholder app icons on the first build, then runs tauri-build, which
//! reads tauri.conf.json. The repository keeps no binary files, so `icons/` is generated and
//! git-ignored; replace the placeholders with your own icons (for example with `cargo tauri icon`),
//! and the script keeps them.

use std::{fs, io, path::Path};

/// The placeholder icons' width and height in pixels.
const ICON_SIZE: u32 = 128;

/// Writes the placeholder icons when they are missing, then runs tauri-build.
fn main() {
    println!("cargo:rerun-if-changed=icons/icon.png");
    println!("cargo:rerun-if-changed=icons/icon.ico");
    if let Err(error) = write_placeholder_icons(Path::new("icons")) {
        panic!("could not write the placeholder icons: {error}");
    }
    tauri_build::build();
}

/// Writes `icon.png` (window and tray, macOS and Linux) and `icon.ico` (Windows resources), each
/// only when missing.
fn write_placeholder_icons(icons_dir: &Path) -> io::Result<()> {
    fs::create_dir_all(icons_dir)?;
    let rgba = circle_rgba(ICON_SIZE);

    let png_path = icons_dir.join("icon.png");
    if !png_path.exists() {
        let file = io::BufWriter::new(fs::File::create(&png_path)?);
        let mut encoder = png::Encoder::new(file, ICON_SIZE, ICON_SIZE);
        encoder.set_color(png::ColorType::Rgba);
        encoder.set_depth(png::BitDepth::Eight);
        let mut writer = encoder.write_header().map_err(io::Error::other)?;
        writer.write_image_data(&rgba).map_err(io::Error::other)?;
        writer.finish().map_err(io::Error::other)?;
    }

    let ico_path = icons_dir.join("icon.ico");
    if !ico_path.exists() {
        let mut icon_dir = ico::IconDir::new(ico::ResourceType::Icon);
        let image = ico::IconImage::from_rgba_data(ICON_SIZE, ICON_SIZE, rgba);
        icon_dir.add_entry(ico::IconDirEntry::encode(&image)?);
        icon_dir.write(io::BufWriter::new(fs::File::create(&ico_path)?))?;
    }
    Ok(())
}

/// A blue disc on a transparent square, with a smoothed edge, as RGBA rows.
fn circle_rgba(size: u32) -> Vec<u8> {
    let center = size as f64 / 2.0;
    let radius = center - 4.0;
    let mut rgba = Vec::with_capacity((size * size * 4) as usize);
    for y in 0..size {
        for x in 0..size {
            let distance =
                ((x as f64 + 0.5 - center).powi(2) + (y as f64 + 0.5 - center).powi(2)).sqrt();
            let coverage = (radius - distance + 0.5).clamp(0.0, 1.0);
            rgba.extend_from_slice(&[0x25, 0x63, 0xeb, (coverage * 255.0).round() as u8]);
        }
    }
    rgba
}
