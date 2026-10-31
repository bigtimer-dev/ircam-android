"""Generate reference values from ircam-linux for the Kotlin unit tests.

usage: python3 tools/make_test_vectors.py ~/Work/ircam-linux > app/src/test/resources/vectors.json
"""
import json, math, sys
sys.path.insert(0, sys.argv[1])
import numpy as np
from ircam import thermal, rawfiles, cameras as C

def pool2_frame():
    h, w = 192, 256
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float64)
    scene = 22.0 + 4.0 * (yy / 192) + 1.5 * np.sin(xx / 25.0)
    scene += 48.0 * np.exp(-(((xx - 150) ** 2 + (yy - 80) ** 2) / (2 * 14.0 ** 2)))
    scene -= 14.0 * np.exp(-(((xx - 200) ** 2 + (yy - 50) ** 2) / (2 * 10.0 ** 2)))
    raw16 = np.clip((scene + 273.15) * 64.0, 0, 65535).astype("<u2")
    frame = np.zeros((384, 256), "<u2")
    frame[:192] = (raw16 >> 6).astype("<u2")
    frame[192:] = raw16
    return frame.tobytes()

out = {}
raw = pool2_frame()
cam = thermal.CameraState()
cam.pool, cam.width, cam.height = C.POOL_2, 256, 384
cam.meta_rows = C.metadata_rows(256, 384)
cam.frame_offsets_for_pool(); cam.init_constants()
thermal.read_camera_config(cam, None)
cam.emissivity, cam.ambient, cam.reflected, cam.humidity, cam.distance, cam.temp_correction = 0.9, 20.0, 30.0, 0.5, 3.0, 0.5
thermal.generate_lut(cam)
td = thermal.decode_frame(cam, raw)
thermal.read_metadata(cam, td)
conv = lambda r: float(thermal.to_celsius(cam, r))
pix = [(0, 0), (150, 80), (200, 50), (255, 191), (17, 133)]
out["pool2"] = {
    "tmax": conv(cam.tmax_raw), "tmin": conv(cam.tmin_raw), "center": conv(cam.center_raw), "avg": conv(cam.tavg_raw),
    "tmax_xy": [cam.tmax_x, cam.tmax_y], "tmin_xy": [cam.tmin_x, cam.tmin_y],
    "pixels": [[x, y, conv(int(td[y * 256 + x]))] for x, y in pix],
    "corr_factor": cam.corr_factor, "corr_offset": cam.corr_offset,
}
meta = rawfiles.RawMeta(2, cam.meta_rows, cam.width_offset, cam.height_offset, 0.5, 20.0, 30.0, 0.5, 0.9, 3)
buf = rawfiles.build_raw_frame(raw, 256, 384, meta)
s = (384 - 1) * 256 * 3
out["id_row_hex"] = bytes(buf[s:s + 39]).hex()

cam1 = thermal.CameraState()
cam1.pool, cam1.width, cam1.height = C.POOL_1, 256, 196
cam1.meta_rows = C.metadata_rows(256, 196)
cam1.init_constants()
cam1.cal = [8000.0, 1.0, 0.0, 0.0, 0.0, 0.01]
cam1.temp_shutter, cam1.temp_fpa = 30.0, 35.0
cam1.emissivity, cam1.ambient, cam1.reflected, cam1.humidity, cam1.distance = 0.95, 22.0, 24.0, 0.4, 2.0
thermal.generate_lut(cam1)
out["pool1_lut"] = [[i, float(cam1.lut[i])] for i in (0, 4000, 8000, 9000, 12000, 16383)]
out["pool1_md"] = [cam1.md1, cam1.md2, cam1.meta_rows]
print(json.dumps(out, indent=1))
