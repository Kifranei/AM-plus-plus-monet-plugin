# 编译用宿主参考 / Compile-time host reference

`ampp-plugin-api-v1.jar` 固定于 AM++ 插件系统 SDK v1（`75dde33e51c6c724c4697d25f5c7788b3e7a8099`），仅作 `compileOnly`，不会打进插件。
The jar is the AM++ plugin SDK v1 public API pinned to the commit above; it is `compileOnly` and never bundled.

执行 `python3 -B scripts/verify-sdk.py` 校验 SHA-256 与类前缀。Run it to check the SHA-256 and class prefix.
