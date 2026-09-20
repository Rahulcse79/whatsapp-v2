# Unit tests. The config/path tests are pure and always built; the Lyra adapter test is
# built only when the Lyra archive is available (it links liblyra.a).

function(add_logic_test name)
  add_executable(${name} ${name}.cpp)
  target_link_libraries(${name} PRIVATE fslyra_logic fslyra_warnings)
  target_include_directories(${name} PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")
  add_test(NAME ${name} COMMAND ${name})
endfunction()

add_logic_test(test_params)
add_logic_test(test_record_path)
add_logic_test(test_codec_config)
add_logic_test(test_record_config)

# The adapter test needs the real library and the model files.
find_package(LyraArchive QUIET)
if(LyraArchive_FOUND)
  add_executable(test_lyra_adapter test_lyra_adapter.cpp
    "${CMAKE_SOURCE_DIR}/src/mod_lyra/lyra_adapter.cpp")
  target_link_libraries(test_lyra_adapter PRIVATE Lyra::Lyra fslyra_common)
  target_include_directories(test_lyra_adapter PRIVATE
    "${CMAKE_SOURCE_DIR}/src" "${CMAKE_CURRENT_SOURCE_DIR}")
  # Model files ship with the repo at third_party/lyra/lyra/model_coeffs.
  add_test(NAME test_lyra_adapter
    COMMAND test_lyra_adapter "${CMAKE_SOURCE_DIR}/../third_party/lyra/lyra/model_coeffs")
else()
  message(STATUS "Lyra archive not found; skipping test_lyra_adapter (run scripts/build-lyra.sh)")
endif()
