cmake_minimum_required(VERSION 3.22.1)
project(novastep C)
add_library(novastep SHARED novastep.c)
target_link_libraries(novastep dl)
