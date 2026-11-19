Import("env")
import os

def merge_bin_action(source, target, env):
    build_dir = env.subst("$BUILD_DIR")
    env_name = env.subst("$PIOENV")
    board_mcu = env.BoardConfig().get("build.mcu", "esp32s3")
    flash_mode = env.BoardConfig().get("build.flash_mode", "dio")
    flash_freq = env.BoardConfig().get("build.f_flash", "80000000L")
    flash_size = env.BoardConfig().get("upload.flash_size", "8MB")
    freq_mhz = "80m" if "80" in str(flash_freq) else "40m"

    segments = {}
    for off, img in env.get("FLASH_EXTRA_IMAGES", []):
        p = env.subst(img)
        if os.path.exists(p):
            segments[int(str(off), 0)] = p

    segments.setdefault(0x0, os.path.join(build_dir, "bootloader.bin"))
    segments.setdefault(0x8000, os.path.join(build_dir, "partitions.bin"))
    app_offset = int(str(env.BoardConfig().get("upload.offset_address", "0x10000")), 0)
    segments[app_offset] = os.path.join(build_dir, "firmware.bin")

    seg_args = " ".join(
        f'0x{off:x} "{path}"' for off, path in sorted(segments.items())
    )
    out_path = os.path.join(build_dir, f"stump_{env_name}_merged.bin")

    esptool_py = os.path.join(
        env.PioPlatform().get_package_dir("tool-esptoolpy"), "esptool.py"
    )

    cmd = (
        f'"{env.subst("$PYTHONEXE")}" "{esptool_py}" --chip {board_mcu} merge_bin '
        f'-o "{out_path}" --flash_mode {flash_mode} --flash_freq {freq_mhz} '
        f'--flash_size {flash_size} {seg_args}'
    )
    print(f"[STUMP] Generating Android USB-OTG merged firmware: {out_path}")
    rc = env.Execute(cmd)
    if rc != 0:
        env.Exit(rc)

env.AddPostAction("$BUILD_DIR/${PROGNAME}.bin", merge_bin_action)
