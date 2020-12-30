/*****************************************************************************
 * Free42 -- an HP-42S calculator simulator
 * Copyright (C) 2004-2021  Thomas Okken
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License, version 2,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, see http://www.gnu.org/licenses/.
 *****************************************************************************/

#include <errno.h>
#include <stdio.h>
#include <string.h>

#ifdef WINDOWS
#define KEYMAP_CC "keymap.cpp"
#define KEYMAP2CC "keymap2cpp"
#else
#define KEYMAP_CC "keymap.cc"
#define KEYMAP2CC "keymap2cc"
#endif

FILE *out;

void write_bytes(FILE *file) {
    int pos;
    int first = 1;
    int c;
    while ((c = fgetc(file)) != EOF) {
        int width = c < 10 ? 1 : c < 100 ? 2 : 3;
        if (first) {
            first = 0;
            fprintf(out, "    ");
            pos = 4;
        } else if (pos + width > 74) {
            fprintf(out, ",\n    ");
            pos = 4;
        } else {
            fprintf(out, ", ");
            pos += 2;
        }
        fprintf(out, "%d", c);
        pos += width;
    }
}

int main(int argc, char *argv[]) {
    FILE *inp;

    out = fopen(KEYMAP_CC, "w");
    if (out == NULL) {
        fprintf(stderr, "%s: can't open output file \"%s\"\n", KEYMAP2CC, KEYMAP_CC);
        return 1;
    }

    fprintf(out,
        "/* %s\n"
        " * Contains the default key map for Free42.\n"
        " * This file is generated by the %s program from the keymap.txt file.\n"
        " * NOTE: this is a generated file; do not edit!\n"
        " */\n\n", KEYMAP_CC, KEYMAP2CC);

        fprintf(out, "#if defined(WINDOWS) && !defined(__GNUC__)\n");
        fprintf(out, "/* Disable warning 'initializing' : truncation from 'const int ' to 'const char ' */\n");
        fprintf(out, "#pragma warning(disable: 4305)\n");
        fprintf(out, "/* Disable warning 'initializing' : truncation of constant value */\n");
        fprintf(out, "#pragma warning(disable: 4309)\n");
        fprintf(out, "#endif\n\n");


    fprintf(out, "/***********************/\n");
    fprintf(out, "/* Size of keymap file */\n");
    fprintf(out, "/***********************/\n\n");
    
    inp = fopen("keymap.txt", "rb");
    if (inp == NULL) {
        int err = errno;
        fprintf(stderr, "Can't open \"keymap.txt\": %s (%d)\n",
                        strerror(err), err);
        fclose(out);
        remove(KEYMAP_CC);
        return 1;
    }
    fseek(inp, 0, SEEK_END);
    fprintf(out, "long keymap_filesize = %ld;\n\n\n", ftell(inp));
    fclose(inp);


    fprintf(out, "/********************/\n");
    fprintf(out, "/* Keymap file data */\n");
    fprintf(out, "/********************/\n\n");

    inp = fopen("keymap.txt", "rb");
    if (inp == NULL) {
        int err = errno;
        fprintf(stderr, "Can't open \"keymap.txt\": %s (%d)\n",
                        strerror(err), err);
        fclose(out);
        remove(KEYMAP_CC);
        return 1;
    }
    // TODO: If I put 'const' here, the symbol is not exported. Why?
    fprintf(out, "/*const*/ char keymap_filedata[] = {\n");
    write_bytes(inp);
    fprintf(out, "\n};\n\n\n");
    fclose(inp);


    fprintf(out, "/***********/\n");
    fprintf(out, "/* The End */\n");
    fprintf(out, "/***********/\n");
    fclose(out);
    return 0;
}
