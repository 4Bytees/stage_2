package com.fourbytees.indexing;

import com.fourbytees.indexing.parser.HeaderParser;
import com.fourbytees.indexing.parser.HeaderParser.BookMetadata;
import com.fourbytees.indexing.repository.SqliteRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class HeaderParserTest {

    @Test
    void testHeaderMultilineTitleAndAuthorEnglish(@TempDir Path tempDir) throws Exception {
        String headerContent = """
            The Project Gutenberg eBook of The History of the Decline and Fall of the
                Roman Empire, Vol. 1
            
            Title: The History of the Decline and Fall of the
                Roman Empire, Vol. 1
            Author: Edward Gibbon
                Edited by J.B. Bury
            Language: English
            Release date: October 12, 1997 [eBook #731]
            Most recently updated: February 7, 2021
            """;

        Path headerFile = tempDir.resolve("gibbon.header.txt");
        Files.writeString(headerFile, headerContent);

        BookMetadata meta = HeaderParser.parse(731, headerFile);

        assertEquals(731, meta.bookId());
        assertEquals("The History of the Decline and Fall of the Roman Empire, Vol. 1", meta.title(),
                "Debe soportar líneas de continuación indentadas en Title");
        assertEquals("Edward Gibbon Edited by J.B. Bury", meta.author(),
                "Debe soportar líneas de continuación indentadas en Author");
        assertEquals("en", meta.language(), "Debe mapear English -> en");
        assertEquals(1997, meta.year(), "Debe extraer el año 1997 de Release date");
    }

    @Test
    void testHeaderSpanishLanguage(@TempDir Path tempDir) throws Exception {
        String headerContent = """
            Title: Don Quijote de la Mancha
            Author: Miguel de Cervantes Saavedra
            Language: Spanish
            Posting date: July 27, 1999 [EBook #2000]
            """;

        Path headerFile = tempDir.resolve("quijote.header.txt");
        Files.writeString(headerFile, headerContent);

        BookMetadata meta = HeaderParser.parse(2000, headerFile);

        assertEquals(2000, meta.bookId());
        assertEquals("Don Quijote de la Mancha", meta.title());
        assertEquals("Miguel de Cervantes Saavedra", meta.author());
        assertEquals("es", meta.language(), "Debe mapear Spanish -> es");
        assertEquals(1999, meta.year(), "Debe extraer el año 1999 de Posting date");
    }

    @Test
    void testHeaderWithoutReleaseDateYieldsNullYear(@TempDir Path tempDir) throws Exception {
        String headerContent = """
            Title: Ancient Fragment
            Author: Anonymous
            Language: French
            Note: No release date provided in this ancient transcription
            """;

        Path headerFile = tempDir.resolve("ancient.header.txt");
        Files.writeString(headerFile, headerContent);

        BookMetadata meta = HeaderParser.parse(9999, headerFile);

        assertEquals(9999, meta.bookId());
        assertEquals("Ancient Fragment", meta.title());
        assertEquals("Anonymous", meta.author());
        assertEquals("fr", meta.language(), "Debe mapear French -> fr");
        assertNull(meta.year(), "Si no hay Release date el año debe ser null");
    }

    @Test
    void testHeaderWithUnknownLanguageYieldsNull(@TempDir Path tempDir) throws Exception {
        String headerContent = """
            Title: Esperanto Gramatiko
            Author: L. L. Zamenhof
            Language: Esperanto
            Release date: January 1, 2010
            """;

        Path headerFile = tempDir.resolve("esperanto.header.txt");
        Files.writeString(headerFile, headerContent);

        BookMetadata meta = HeaderParser.parse(8888, headerFile);

        assertEquals(8888, meta.bookId());
        assertNull(meta.language(), "Idioma no soportado debe resultar en null");
        assertEquals(2010, meta.year());
    }

    @Test
    void testSqliteRepositoryStoresNullYear(@TempDir Path tempDir) throws Exception {
        Path dbPath = tempDir.resolve("test_null_year.db");
        SqliteRepository repo = new SqliteRepository(dbPath.toString());

        BookMetadata meta = new BookMetadata(555, "Null Year Book", "Author Unknown", "en", null);
        Map<String, List<Integer>> postings = Collections.singletonMap("test", List.of(1));

        repo.saveBookIndex(meta, postings);

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT year FROM books WHERE book_id = 555")) {

            assertTrue(rs.next());
            rs.getInt("year");
            assertTrue(rs.wasNull(), "La columna year en la base de datos debe ser NULL y no 0");
        }
    }
}

