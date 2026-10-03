package me.neznamy.tab.shared.config.mysql;

import org.junit.jupiter.api.Test;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MySQLTest {
    @Test
    void failedQueryClosesPreparedStatement() throws Exception {
        MySQL database = new MySQL(mock(MySQLConfiguration.class));
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        var field = MySQL.class.getDeclaredField("con");
        field.setAccessible(true);
        field.set(database, connection);
        when(connection.prepareStatement("SELECT 1")).thenReturn(statement);
        when(statement.executeQuery()).thenThrow(new SQLException("query failed"));
        assertThrows(SQLException.class, () -> database.getCRS("SELECT 1"));
        verify(statement).close();
    }
}
