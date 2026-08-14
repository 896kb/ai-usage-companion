# R8 strips the reflectively-constructed Shizuku user service otherwise, and
# the failure is a runtime InstantiationException that will not show up in a
# debug build.
-keep class dev.rgarjoaba.claudestatus.rear.RearUserService { *; }
-keep interface dev.rgarjoaba.claudestatus.IRearService { *; }
-keep class dev.rgarjoaba.claudestatus.IRearService$Stub { *; }
