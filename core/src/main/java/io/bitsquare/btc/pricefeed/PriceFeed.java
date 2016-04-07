package io.bitsquare.btc.pricefeed;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.SettableFuture;
import com.google.inject.Inject;
import io.bitsquare.app.Log;
import io.bitsquare.btc.pricefeed.providers.BitcoinAveragePriceProvider;
import io.bitsquare.btc.pricefeed.providers.PoloniexPriceProvider;
import io.bitsquare.btc.pricefeed.providers.PriceProvider;
import io.bitsquare.common.UserThread;
import io.bitsquare.common.handlers.FaultHandler;
import io.bitsquare.locale.CurrencyUtil;
import javafx.beans.property.*;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

public class PriceFeed {
    private static final Logger log = LoggerFactory.getLogger(PriceFeed.class);

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Enum
    ///////////////////////////////////////////////////////////////////////////////////////////

    public enum Type {
        ASK("Ask"),
        BID("Bid"),
        LAST("Last");

        public final String name;

        Type(String name) {
            this.name = name;
        }
    }

    private static final long PERIOD_FIAT_SEC = 60;
    private static final long PERIOD_ALL_FIAT_SEC = 60 * 5;
    private static final long PERIOD_CRYPTO_SEC = 60;
    private static final long PERIOD_ALL_CRYPTO_SEC = 60 * 5;

    private final Map<String, MarketPrice> cache = new HashMap<>();
    private final PriceProvider fiatPriceProvider = new BitcoinAveragePriceProvider();
    private final PriceProvider cryptoCurrenciesPriceProvider = new PoloniexPriceProvider();
    private Consumer<Double> priceConsumer;
    private FaultHandler faultHandler;
    private Type type;
    private String currencyCode;
    private final StringProperty currencyCodeProperty = new SimpleStringProperty();
    private final ObjectProperty<Type> typeProperty = new SimpleObjectProperty<>();
    private final IntegerProperty currenciesUpdateFlag = new SimpleIntegerProperty(0);


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public PriceFeed() {
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void init(Consumer<Double> resultHandler, FaultHandler faultHandler) {
        this.priceConsumer = resultHandler;
        this.faultHandler = faultHandler;

        requestAllPrices(fiatPriceProvider, () -> {
            applyPrice();
            UserThread.runPeriodically(() -> requestPrice(fiatPriceProvider), PERIOD_FIAT_SEC);
        });

        requestAllPrices(cryptoCurrenciesPriceProvider, () -> {
            applyPrice();
            UserThread.runPeriodically(() -> requestAllPrices(cryptoCurrenciesPriceProvider, this::applyPrice),
                    PERIOD_CRYPTO_SEC);
        });

        UserThread.runPeriodically(() -> requestAllPrices(fiatPriceProvider, this::applyPrice), PERIOD_ALL_FIAT_SEC);
        UserThread.runPeriodically(() -> requestAllPrices(cryptoCurrenciesPriceProvider, this::applyPrice), PERIOD_ALL_CRYPTO_SEC);

        requestAllPrices(cryptoCurrenciesPriceProvider, this::applyPrice);
    }

    @Nullable
    public MarketPrice getMarketPrice(String currencyCode) {
        if (cache.containsKey(currencyCode))
            return cache.get(currencyCode);
        else
            return null;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Setter
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void setType(Type type) {
        this.type = type;
        typeProperty.set(type);
        applyPrice();
    }

    public void setCurrencyCode(String currencyCode) {
        this.currencyCode = currencyCode;
        currencyCodeProperty.set(currencyCode);
        applyPrice();

        if (CurrencyUtil.isFiatCurrency(currencyCode))
            requestPrice(fiatPriceProvider);
        else
            requestPrice(cryptoCurrenciesPriceProvider);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Getter
    ///////////////////////////////////////////////////////////////////////////////////////////

    public Type getType() {
        return type;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public StringProperty currencyCodeProperty() {
        return currencyCodeProperty;
    }

    public ObjectProperty<Type> typeProperty() {
        return typeProperty;
    }

    public IntegerProperty currenciesUpdateFlagProperty() {
        return currenciesUpdateFlag;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void applyPrice() {
        if (priceConsumer != null && currencyCode != null && type != null) {
            if (cache.containsKey(currencyCode)) {
                MarketPrice marketPrice = cache.get(currencyCode);
                //log.debug("applyPrice type=" + type);
                if (marketPrice != null)
                    priceConsumer.accept(marketPrice.getPrice(type));
            } else {
                String errorMessage = "We don't have a price for currencyCode " + currencyCode;
                log.debug(errorMessage);
                faultHandler.handleFault(errorMessage, new PriceRequestException(errorMessage));
            }
        }
        currenciesUpdateFlag.setValue(currenciesUpdateFlag.get() + 1);
    }

    private void requestPrice(PriceProvider provider) {
        Log.traceCall();
        GetPriceRequest getPriceRequest = new GetPriceRequest();
        SettableFuture<MarketPrice> future = getPriceRequest.requestPrice(currencyCode, provider);
        Futures.addCallback(future, new FutureCallback<MarketPrice>() {
            public void onSuccess(MarketPrice marketPrice) {
                UserThread.execute(() -> {
                    cache.put(marketPrice.currencyCode, marketPrice);
                    //log.debug("marketPrice updated " + marketPrice);
                    priceConsumer.accept(marketPrice.getPrice(type));
                });
            }

            public void onFailure(@NotNull Throwable throwable) {
                log.debug("Could not load marketPrice\n" + throwable.getMessage());
            }
        });
    }

    private void requestAllPrices(PriceProvider provider, @Nullable Runnable resultHandler) {
        Log.traceCall();
        GetPriceRequest getPriceRequest = new GetPriceRequest();
        SettableFuture<Map<String, MarketPrice>> future = getPriceRequest.requestAllPrices(provider);
        Futures.addCallback(future, new FutureCallback<Map<String, MarketPrice>>() {
            public void onSuccess(Map<String, MarketPrice> marketPriceMap) {
                UserThread.execute(() -> {
                    cache.putAll(marketPriceMap);
                    if (resultHandler != null)
                        resultHandler.run();
                });
            }

            public void onFailure(@NotNull Throwable throwable) {
                UserThread.execute(() -> faultHandler.handleFault("Could not load marketPrices", throwable));
            }
        });
    }
}
