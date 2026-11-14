# Optional Carbon mods (launcher switches), loaded before libmain.so only when a switch is on. See carbon_mods.c.
# Separate from ../cpp (the full native build of upstream): the launcher-only build compiles just this library.
cmake_minimum_required(VERSION 3.22)
project(carbonmods C)
add_library(carbonmods SHARED carbon_mods.c)
# DF_1_GLOBAL puts the library in the namespace's global group, so libmain.so's sub_<address> relocations bind here.
target_link_options(carbonmods PRIVATE -Wl,-z,global)
target_link_libraries(carbonmods log dl)
