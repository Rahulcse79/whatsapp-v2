// A 40-line test harness: no gtest, no network, no FreeSWITCH. Each test file defines
// cases with TEST(name){...} and CHECK(cond); main() runs them and reports.
#pragma once

#include <cstdio>
#include <functional>
#include <string>
#include <vector>

namespace check {

struct Case {
    std::string name;
    std::function<void()> fn;
};

inline std::vector<Case>& cases() {
    static std::vector<Case> c;
    return c;
}

inline int& failures() {
    static int f = 0;
    return f;
}

struct Registrar {
    Registrar(const std::string& name, std::function<void()> fn) { cases().push_back({name, std::move(fn)}); }
};

inline void fail(const char* file, int line, const std::string& msg) {
    std::fprintf(stderr, "    FAIL %s:%d: %s\n", file, line, msg.c_str());
    ++failures();
}

}  // namespace check

#define TEST(name)                                                        \
    static void name();                                                   \
    static ::check::Registrar reg_##name(#name, name);                    \
    static void name()

#define CHECK(cond)                                                       \
    do {                                                                  \
        if (!(cond)) ::check::fail(__FILE__, __LINE__, "CHECK(" #cond ")"); \
    } while (0)

#define CHECK_EQ(a, b)                                                    \
    do {                                                                  \
        auto _a = (a);                                                    \
        auto _b = (b);                                                    \
        if (!(_a == _b)) ::check::fail(__FILE__, __LINE__, "CHECK_EQ(" #a ", " #b ")"); \
    } while (0)

#define RUN_ALL_TESTS_MAIN()                                              \
    int main() {                                                          \
        int run = 0;                                                      \
        int failed_before = 0;                                            \
        for (auto& c : ::check::cases()) {                                \
            failed_before = ::check::failures();                          \
            c.fn();                                                       \
            ++run;                                                        \
            std::printf("  %-40s %s\n", c.name.c_str(),                   \
                        ::check::failures() == failed_before ? "ok" : "FAILED"); \
        }                                                                 \
        std::printf("%d test(s), %d failure(s)\n", run, ::check::failures()); \
        return ::check::failures() == 0 ? 0 : 1;                          \
    }
