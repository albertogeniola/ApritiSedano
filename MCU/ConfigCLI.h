#ifndef CONFIG_CLI_H
#define CONFIG_CLI_H

#include <Arduino.h>

class ConfigCLI {
public:
    static void init();
    static void handle();
private:
    static void showMenu();
    static void configureMasterClient();
    static void managePairedClients();
    static String readString();
};

#endif
