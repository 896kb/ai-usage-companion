# R8 strips the reflectively-constructed Shizuku user service otherwise, and
# the failure is a runtime InstantiationException that will not show up in a
# debug build.
-keep class dev.kb896.aiusage.rear.RearUserService { *; }
-keep interface dev.kb896.aiusage.IRearService { *; }
-keep class dev.kb896.aiusage.IRearService$Stub { *; }
