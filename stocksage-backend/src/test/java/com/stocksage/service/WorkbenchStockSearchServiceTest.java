package com.stocksage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.client.DataServiceClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WorkbenchStockSearchServiceTest {

    private final DataServiceClient dataServiceClient = mock(DataServiceClient.class);
    private final WorkbenchStockSearchService service = new WorkbenchStockSearchService(
            dataServiceClient,
            new ObjectMapper()
    );

    @Test
    void mapsDataServiceCandidatesIntoWorkbenchSuggestions() {
        when(dataServiceClient.searchStocks("amazon", 8)).thenReturn("""
                {
                  "query": "amazon",
                  "count": 1,
                  "candidates": [
                    {
                      "resolvedCode": "AMZN",
                      "symbol": "AMZN",
                      "name": "Amazon.com Inc.",
                      "market": "US",
                      "source": "ibkr"
                    }
                  ]
                }
                """);

        var suggestions = service.search("amazon", 8);

        assertThat(suggestions).hasSize(1);
        assertThat(suggestions.get(0).ticker()).isEqualTo("AMZN");
        assertThat(suggestions.get(0).name()).isEqualTo("Amazon.com Inc.");
        assertThat(suggestions.get(0).market()).isEqualTo("US");
        assertThat(suggestions.get(0).source()).isEqualTo("ibkr");
    }

    @Test
    void normalizesRoutedAshareAndHkCodesForTheWatchlistInputContract() {
        when(dataServiceClient.searchStocks("ma", 8)).thenReturn("""
                {
                  "query": "ma",
                  "count": 2,
                  "candidates": [
                    {
                      "resolvedCode": "sh.600519",
                      "name": "Kweichow Moutai",
                      "market": "A_SHARE",
                      "source": "baostock"
                    },
                    {
                      "resolvedCode": "0700.HK",
                      "shortName": "Tencent",
                      "market": "HK",
                      "source": "akshare"
                    }
                  ]
                }
                """);

        var suggestions = service.search("ma", 8);

        assertThat(suggestions).extracting("ticker").containsExactly("600519", "0700");
        assertThat(suggestions.get(1).name()).isEqualTo("Tencent");
    }

    @Test
    void returnsEmptySuggestionsForBlankQueriesWithoutCallingTheDataService() {
        assertThat(service.search("   ", 8)).isEmpty();
        verifyNoInteractions(dataServiceClient);
    }
}
