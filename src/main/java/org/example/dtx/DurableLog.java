package org.example.dtx;

import java.io.IOException;
import java.util.List;

interface DurableLog {
    void append(String jsonLine) throws IOException;

    List<String> readAll() throws IOException;
}
