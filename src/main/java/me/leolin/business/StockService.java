package me.leolin.business;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.leolin.Config;
import me.leolin.dao.OtcIndustryDao;
import me.leolin.dao.StockDao;
import me.leolin.dao.StockPriceDao;
import me.leolin.dao.TseIndustryDao;
import me.leolin.model.dto.stock.IndustryDto;
import me.leolin.model.dto.stock.StockDto;
import me.leolin.model.entity.OtcIndustryEntity;
import me.leolin.model.entity.StockEntity;
import me.leolin.model.entity.StockPriceEntity;
import me.leolin.model.entity.TseIndustryEntity;
import me.leolin.model.holder.stock.RemoteStockPriceHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import rx.util.async.Async;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static java.util.stream.Collectors.toList;
import static rx.schedulers.Schedulers.io;

/**
 * @author leolin
 */
@Service
public class StockService {
    private static final Logger LOGGER = LoggerFactory.getLogger(StockService.class);
    public static final String KEY_TSE = "tse";
    public static final String KEY_OTC = "otc";
    public static final String KEY_CODE = "code";
    public static final String KEY_NAME = "name";
    public static final String KEY_STOCK_ID = "ch";
    public static final String KEY_EX = "ex";
    public static final String KEY_STOCK_FULL_NAME = "nf";
    public static final String KEY_STOCK_NAME = "n";
    public static final String KEY_MSG_ARRAY = "msgArray";


    @Autowired
    private RestTemplate restTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private TseIndustryDao tseIndustryDao;
    @Autowired
    private OtcIndustryDao otcIndustryDao;
    @Autowired
    private StockDao stockDao;
    @Autowired
    private StockPriceDao stockPriceDao;


    private void syncStocksInIndustry(String marketCode, String industryCode) {
        try {
            ResponseEntity<String> responseEntity = restTemplate.getForEntity(String.format(Config.URL_GET_STOCKS_BY_INDUSTRY, marketCode, industryCode), String.class);
            List<Map<String, String>> list = (List<Map<String, String>>) objectMapper.readValue(responseEntity.getBody(), Map.class).get(KEY_MSG_ARRAY);

            stockDao.save(
                    list.stream()
                            .map(m -> new StockEntity(m.get(KEY_STOCK_ID), m.get(KEY_EX), m.get(KEY_STOCK_FULL_NAME), m.get(KEY_STOCK_NAME), industryCode))
                            .filter(this::filterStockEntity)
                            .collect(toList()));
        } catch (IOException e) {
            LOGGER.error(e.getMessage(), e);
        }
    }

    private boolean filterStockEntity(StockEntity stockEntity) {

        try {
            Integer.parseInt(stockEntity.getId().split("\\.")[0]);
            String fullName = stockEntity.getFullName();
            if (fullName.contains("美購") || fullName.contains("歐購") || fullName.contains("公司債")) {
                return false;
            }
        } catch (Exception e) {
            return false;
        }
        return true;
    }

    public List<IndustryDto> getAllTseIndustry() {
        return tseIndustryDao.findAll().stream().map(e -> new IndustryDto(e.getCode(), e.getName())).collect(toList());
    }

    public List<IndustryDto> getAllOtcIndustry() {
        return otcIndustryDao.findAll().stream().map(e -> new IndustryDto(e.getCode(), e.getName())).collect(toList());
    }

    public List<StockDto> getStockByMarkeyAndIndustry(String marketCode, String industryCode) {
        return stockDao.findByMarketAndIndustry(marketCode, industryCode).stream().map(e -> new StockDto(e.getId(), e.getName(), e.getFullName())).collect(toList());
    }


    @Scheduled(cron = "0 0 1 * * ?")
    @Transactional
    public void syncStocks() {
        try {
            tseIndustryDao.deleteAllInBatch();
            otcIndustryDao.deleteAllInBatch();
            stockDao.deleteAllInBatch();

            ResponseEntity<String> responseEntity = restTemplate.getForEntity(Config.URL_GET_INDUSTRY, String.class);
            Map<String, List<Map<String, String>>> map = objectMapper.readValue(responseEntity.getBody(), Map.class);

            List<TseIndustryEntity> tseIndustryEntities = map.get(KEY_TSE).stream()
                    .map(m -> new TseIndustryEntity(m.get(KEY_CODE), m.get(KEY_NAME)))
                    .collect(toList());
            List<OtcIndustryEntity> otcIndustryEntities = map.get(KEY_OTC).stream()
                    .map(m -> new OtcIndustryEntity(m.get(KEY_CODE), m.get(KEY_NAME)))
                    .collect(toList());


            tseIndustryDao.save(tseIndustryEntities);
            otcIndustryDao.save(otcIndustryEntities);


            tseIndustryEntities.forEach(e -> syncStocksInIndustry(KEY_TSE, e.getCode()));
            otcIndustryEntities.forEach(e -> syncStocksInIndustry(KEY_OTC, e.getCode()));
        } catch (IOException e) {
            LOGGER.error(e.getMessage(), e);
        }
    }

    //    @Scheduled(cron = "0/20 0 9-12 * MON-FRI ?")
//    @Scheduled(cron = "0/20 0-35 13 * MON-FRI ?")
    @Scheduled(fixedRate = 60000, initialDelay = 5000)
    public void syncStockPrice() {
        LOGGER.info("Start get price");
        tseIndustryDao.getAllIndustryCodes().forEach(code -> {
                    String queryString = String
                            .join("|", (
                                    stockDao.findByMarketAndIndustry(KEY_TSE, code)
                                            .stream()
                                            .map(e -> KEY_TSE + "_" + e.getId())
                                            .collect(toList())
                            ));
                    if (!queryString.isEmpty()) {
                        Async.start(() -> getRemoteStockPrice(queryString), io())
                                .subscribe(stockPriceDao::save);
                    }
                }
        );
        otcIndustryDao.getAllIndustryCodes().forEach(code -> {
                    String queryString = String
                            .join("|", (
                                    stockDao.findByMarketAndIndustry(KEY_OTC, code)
                                            .stream()
                                            .map(e -> KEY_OTC + "_" + e.getId())
                                            .collect(toList())
                            ));
                    if (!queryString.isEmpty()) {
                        Async.start(() -> getRemoteStockPrice(queryString), io())
                                .subscribe(stockPriceEntities -> {
                                    stockPriceDao.save(stockPriceEntities);
                                    LOGGER.info("Successfully save {} price entities", stockPriceEntities.size());
                                });
                    }
                }
        );
    }


    private List<StockPriceEntity> getRemoteStockPrice(String queryString) {
        String url = String.format(Config.URL_GET_STOCK_PIRCE, queryString);
        ResponseEntity<String> responseEntity = restTemplate.getForEntity(url, String.class);
        try {
            RemoteStockPriceHolder holder = objectMapper.readValue(responseEntity.getBody(), RemoteStockPriceHolder.class);
            List<StockPriceEntity> prices = holder.getStockPrices();
            LOGGER.info("Successfully get {} stock price", prices.size());
            return prices;
        } catch (Exception e) {
            LOGGER.error(e.getMessage(), e);
            return new ArrayList<>(0);
        }
    }
}
