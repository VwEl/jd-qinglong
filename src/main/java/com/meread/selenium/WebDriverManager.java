package com.meread.selenium;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.JSONPath;
import com.amihaiemil.docker.Container;
import com.amihaiemil.docker.Containers;
import com.amihaiemil.docker.UnixDocker;
import com.meread.selenium.bean.*;
import com.meread.selenium.util.WebDriverOpCallBack;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.openqa.selenium.By;
import org.openqa.selenium.MutableCapabilities;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.html5.LocalStorage;
import org.openqa.selenium.remote.RemoteExecuteMethod;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.remote.html5.RemoteWebStorage;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * @author yangxg
 * @date 2021/9/7
 */
@Component
@Slf4j
public class WebDriverManager implements CommandLineRunner, InitializingBean {

    @Autowired
    private ResourceLoader resourceLoader;

    @Value("${jd.debug}")
    private boolean debug;

    @Autowired
    private JDService jdService;

    @Value("${selenium.hub.url}")
    private String seleniumHubUrl;

    @Value("${env.path}")
    private String envPath;

    @Value("${selenium.hub.status.url}")
    private String seleniumHubStatusUrl;

    @Value("${op.timeout}")
    private int opTimeout;

    @Value("${chrome.timeout}")
    private int chromeTimeout;

    @Value("#{environment.SE_NODE_MAX_SESSIONS}")
    private String maxSessionFromSystemEnv;

    @Value("${SE_NODE_MAX_SESSIONS}")
    private String maxSessionFromProps;

    private String maxSessionFromEnvFile;

    private List<QLConfig> qlConfigs;

    public Properties properties = new Properties();

    private String xddUrl;

    private String xddToken;

    private static int CAPACITY = 0;

    public volatile boolean stopSchedule = false;
    public volatile boolean initSuccess = false;
    public volatile boolean runningSchedule = false;

    /**
     * chromeSessionId-->MyChrome
     */
    private final Map<String, MyChrome> chromes = Collections.synchronizedMap(new HashMap<>());

    /**
     * userTrackId --> MyChromeClient
     */
    private final Map<String, MyChromeClient> clients = Collections.synchronizedMap(new HashMap<>());

    public ChromeOptions chromeOptions;

    public void init() {
        chromeOptions = new ChromeOptions();
        chromeOptions.setExperimentalOption("excludeSwitches", new String[]{"enable-automation"});
        chromeOptions.setExperimentalOption("useAutomationExtension", true);
        chromeOptions.addArguments("lang=zh-CN,zh,zh-TW,en-US,en");
        chromeOptions.addArguments("user-agent=Mozilla/5.0 (Macintosh; Intel Mac OS X 10_13_5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/67.0.3396.99 Safari/537.36");
        chromeOptions.addArguments("disable-blink-features=AutomationControlled");
        chromeOptions.addArguments("--disable-gpu");
        chromeOptions.setCapability("browserName", "chrome");
        chromeOptions.setCapability("browserVersion", "89.0");
        chromeOptions.setCapability("screenResolution", "1280x1024x24");
        chromeOptions.setCapability("enableVideo", false);
        if (chromeTimeout < 60) {
            chromeTimeout = 60;
        }
        chromeOptions.setCapability("selenoid:options", Map.<String, Object>of(
                "enableVNC", debug,
                "enableVideo", false,
                "enableLog", debug,
                "sessionTimeout", chromeTimeout + "s"
//                "applicationContainers", new String[]{"webapp"}
        ));
        if (!debug) {
            chromeOptions.addArguments("--headless");
        }
        chromeOptions.addArguments("--no-sandbox");
        chromeOptions.addArguments("--disable-extensions");
        chromeOptions.addArguments("--disable-dev-shm-usage");
        chromeOptions.addArguments("--disable-software-rasterizer");
        chromeOptions.addArguments("--ignore-ssl-errors=yes");
        chromeOptions.addArguments("--ignore-certificate-errors");
        chromeOptions.addArguments("--allow-running-insecure-content");
        chromeOptions.addArguments("--window-size=500,700");
    }

    public MutableCapabilities getOptions() {
        return chromeOptions;
    }

    @Scheduled(initialDelay = 60000, fixedDelay = 30 * 60000)
    public void syncCK_count() {
        if (qlConfigs != null) {
            for (QLConfig qlConfig : qlConfigs) {
                int oldSize = qlConfig.getRemain();
                Boolean exec = exec(webDriver -> {
                    jdService.fetchCurrentCKS_count(webDriver, qlConfig, "");
                    return true;
                });
                if (exec != null && exec) {
                    int newSize = qlConfig.getRemain();
                    log.info(qlConfig.getQlUrl() + " 容量从 " + oldSize + "变为" + newSize);
                } else {
                    log.error("syncCK_count 执行失败");
                }
            }
        }
    }

    @Scheduled(cron = "0 0 0 * * ?")
//    @Scheduled(initialDelay = 30000, fixedDelay = 30000)
    public void refreshOpenIdToken() {
        if (qlConfigs != null) {
            for (QLConfig qlConfig : qlConfigs) {
                if (qlConfig.getQlLoginType() == QLConfig.QLLoginType.TOKEN) {
                    QLToken qlTokenOld = qlConfig.getQlToken();
                    jdService.fetchNewOpenIdToken(qlConfig);
                    log.info(qlConfig.getQlToken() + " token 从" + qlTokenOld + " 变为 " + qlConfig.getQlToken());
                }
            }
        }
    }

    /**
     * 和grid同步chrome状态，清理失效的session，并移除本地缓存
     */
    @Scheduled(initialDelay = 10000, fixedDelay = 2000)
    public void heartbeat() {
        runningSchedule = true;
        if (!stopSchedule) {
            SelenoidStatus nss = getGridStatus();
            Iterator<Map.Entry<String, MyChrome>> iterator = chromes.entrySet().iterator();
            Set<String> removedChromeSessionId = new HashSet<>();
            while (iterator.hasNext()) {
                String chromeSessionId = iterator.next().getKey();
                Map<String, JSONObject> sessions = nss.getSessions();
                JSONObject jsonObject = sessions.get(chromeSessionId);
                if (jsonObject != null) {
                    break;
                } else {
                    //如果session不存在，则remove
                    iterator.remove();
                    removedChromeSessionId.add(chromeSessionId);
                    log.warn("quit a chrome");
                }
            }
            cleanClients(removedChromeSessionId);
            int shouldCreate = CAPACITY - chromes.size();
            if (shouldCreate > 0) {
                try {
                    RemoteWebDriver webDriver = new RemoteWebDriver(new URL(seleniumHubUrl), getOptions());
                    MyChrome myChrome = new MyChrome();
                    myChrome.setWebDriver(webDriver);
                    chromes.put(webDriver.getSessionId().toString(), myChrome);
                    log.warn("create a chrome " + webDriver.getSessionId().toString() + " 总容量 = " + CAPACITY + ", 当前容量" + chromes.size());
                } catch (MalformedURLException e) {
                    e.printStackTrace();
                }
                inflate(chromes, getGridStatus());
            }
        }
        runningSchedule = false;
    }

    private void cleanClients(Set<String> removedChromeSessionId) {
        Iterator<Map.Entry<String, MyChromeClient>> iterator = clients.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, MyChromeClient> entry = iterator.next();
            String chromeSessionId = entry.getValue().getChromeSessionId();
            if (chromeSessionId == null || removedChromeSessionId.contains(chromeSessionId)) {
                iterator.remove();
            }
        }
    }

    @Autowired
    private RestTemplate restTemplate;

    public SelenoidStatus getGridStatus() {
        SelenoidStatus status = new SelenoidStatus();
        Map<String, JSONObject> sessions = new HashMap<>();
        String url = seleniumHubStatusUrl;
        String json = restTemplate.getForObject(url, String.class);
        JSONObject data = JSON.parseObject(json);
        int total = data.getIntValue("total");
        int used = data.getIntValue("used");
        int queued = data.getIntValue("queued");
        int pending = data.getIntValue("pending");
        List<JSONObject> read = (List<JSONObject>) JSONPath.read(json, "$.browsers.chrome..sessions");
        if (read != null) {
            for (JSONObject jo : read) {
                sessions.put(jo.getString("id"), jo);
            }
        }
        status.setSessions(sessions);
        status.setUsed(used);
        status.setTotal(total);
        status.setQueued(queued);
        status.setPending(pending);
        return status;
    }

    public void closeSession(String sessionId) {
        String deleteUrl = String.format("%s/session/%s", seleniumHubUrl, sessionId);
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-REGISTRATION-SECRET", "");
        HttpEntity<?> request = new HttpEntity<>(headers);
        ResponseEntity<String> exchange = null;
        try {
            exchange = restTemplate.exchange(deleteUrl, HttpMethod.DELETE, request, String.class);
            log.info("close sessionId : " + deleteUrl + " resp : " + exchange.getBody() + ", resp code : " + exchange.getStatusCode());
        } catch (RestClientException e) {
            e.printStackTrace();
        }
    }

    private void inflate(Map<String, MyChrome> chromes, SelenoidStatus statusList) {
        Map<String, JSONObject> sessions = statusList.getSessions();
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        for (MyChrome chrome : chromes.values()) {
            String currSessionId = chrome.getWebDriver().getSessionId().toString();
            JSONObject sessionInfo = sessions.get(currSessionId);
            if (sessionInfo != null) {
                String id = sessionInfo.getString("id");
                chrome.setSessionInfoJson(sessionInfo);
            }
        }
    }

    @Override
    public void run(String... args) throws MalformedURLException {

        stopSchedule = true;

        log.info("解析配置不初始化");
        parseMultiQLConfig();

        //获取hub-node状态
        SelenoidStatus status = getNodeStatuses();
        if (status == null) {
            throw new RuntimeException("Selenium 浏览器组初始化失败");
        }

        //清理未关闭的session
        log.info("清理未关闭的session，获取最大容量");
        Map<String, JSONObject> sessions = status.getSessions();
        if (sessions != null && sessions.size() > 0) {
            for (String sessionId : sessions.keySet()) {
                if (sessionId != null) {
                    try {
                        closeSession(sessionId);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        }
        //清理未关闭的session
        log.info("清理未关闭的docker container");
        cleanDockerContainer();

        //初始化一半Chrome实例
        log.info("初始化一半Chrome实例");
        createChrome();
        inflate(chromes, getGridStatus());
        //借助这一半chrome实例，初始化配置
        initQLConfig();
        if (qlConfigs.isEmpty()) {
            log.warn("请配置至少一个青龙面板地址! 否则获取到的ck无法上传");
        }

        if (chromes.isEmpty()) {
            //防止配置资源数过少时(比如1)，因为初始化青龙后，导致无chrome实例,来不及等定时任务创建
            createChrome();
            inflate(chromes, getGridStatus());
        }

        log.info("启动成功!");
        stopSchedule = false;
        initSuccess = true;
    }

    private void createChrome() {
        ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        int create = CAPACITY == 1 ? 1 : CAPACITY / 2;
        CountDownLatch cdl = new CountDownLatch(create);
        for (int i = 0; i < create; i++) {
            executorService.execute(() -> {
                try {
                    RemoteWebDriver webDriver = new RemoteWebDriver(new URL(seleniumHubUrl), getOptions());
                    webDriver.manage().timeouts().implicitlyWait(10, TimeUnit.SECONDS).pageLoadTimeout(10, TimeUnit.SECONDS).setScriptTimeout(10, TimeUnit.SECONDS);
                    MyChrome myChrome = new MyChrome();
                    myChrome.setWebDriver(webDriver);
                    //计算chrome实例的最大存活时间
                    myChrome.setExpireTime(System.currentTimeMillis() + (chromeTimeout - 10) * 1000L);
                    chromes.put(webDriver.getSessionId().toString(), myChrome);
                } catch (MalformedURLException e) {
                    e.printStackTrace();
                } finally {
                    cdl.countDown();
                }
            });
        }

        try {
            cdl.await();
        } catch (InterruptedException e) {
            throw new RuntimeException("无法创建浏览器实例");
        }
        executorService.shutdown();

        if (chromes.isEmpty()) {
            throw new RuntimeException("无法创建浏览器实例");
        }
    }

    public void cleanDockerContainer() {
        Containers containers = new UnixDocker(new File("/var/run/docker.sock")).containers();
        for (Container container : containers) {
            String image = container.getString("Image");
            if (image.startsWith("selenoid/chrome")) {
                try {
                    log.info("关闭残留容器" + container.containerId());
                    container.remove(true, true, false);
                } catch (Exception e) {
                }
            }
        }
    }

    private SelenoidStatus getNodeStatuses() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CompletableFuture<SelenoidStatus> startSeleniumRes = CompletableFuture.supplyAsync(() -> {
            while (true) {
                SelenoidStatus ss = getGridStatus();
                if (ss.getTotal() > 0) {
                    return ss;
                }
            }
        }, executor);
        SelenoidStatus status = null;
        try {
            status = startSeleniumRes.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            e.printStackTrace();
        }
        executor.shutdown();
        return status;
    }

    private void parseMultiQLConfig() {
        qlConfigs = new ArrayList<>();
        if (envPath.startsWith("classpath")) {
            Resource resource = resourceLoader.getResource(envPath);
            try (InputStreamReader inputStreamReader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
                properties.load(inputStreamReader);
            } catch (IOException e) {
                throw new RuntimeException("env.properties配置有误");
            }
        } else {
            File envFile = new File(envPath);
            if (!envFile.exists()) {
                throw new RuntimeException("env.properties配置有误");
            }
            try (BufferedReader br = new BufferedReader(new FileReader(envFile, StandardCharsets.UTF_8))) {
                properties.load(br);
            } catch (IOException e) {
                throw new RuntimeException("env.properties配置有误");
            }
        }

        maxSessionFromEnvFile = properties.getProperty("SE_NODE_MAX_SESSIONS");
        String opTime = properties.getProperty("OP_TIME");
        if (!StringUtils.isEmpty(opTime)) {
            try {
                int i = Integer.parseInt(opTime);
                if (i > 0) {
                    opTimeout = i;
                }
            } catch (NumberFormatException e) {
            }
        }

        log.info("最大资源数配置: maxSessionFromEnvFile = " + maxSessionFromEnvFile + ", maxSessionFromSystemEnv = " + maxSessionFromSystemEnv + ", maxSessionFromProps = " + maxSessionFromProps);

        CAPACITY = parseCapacity();
        log.info("最大资源数配置: CAPACITY = " + CAPACITY);
        if (CAPACITY <= 0) {
            throw new RuntimeException("最大资源数配置有误");
        }

        xddUrl = properties.getProperty("XDD_URL");
        xddToken = properties.getProperty("XDD_TOKEN");

        for (int i = 1; i <= 5; i++) {
            QLConfig config = new QLConfig();
            config.setId(i);
            for (String key : properties.stringPropertyNames()) {
                String value = properties.getProperty(key);
                if (key.equals("QL_USERNAME_" + i)) {
                    config.setQlUsername(value);
                } else if (key.equals("QL_URL_" + i)) {
                    if (value.endsWith("/")) {
                        value = value.substring(0, value.length() - 1);
                    }
                    config.setQlUrl(value);
                } else if (key.equals("QL_PASSWORD_" + i)) {
                    config.setQlPassword(value);
                } else if (key.equals("QL_CLIENTID_" + i)) {
                    config.setQlClientID(value);
                } else if (key.equals("QL_SECRET_" + i)) {
                    config.setQlClientSecret(value);
                } else if (key.equals("QL_LABEL_" + i)) {
                    config.setLabel(value);
                } else if (key.equals("QL_CAPACITY_" + i)) {
                    config.setCapacity(Integer.parseInt(value));
                }
            }
            if (config.isValid()) {
                qlConfigs.add(config);
            }
        }
        log.info("解析" + qlConfigs.size() + "套配置");
    }

    private int parseCapacity() {
        int res = 0;
        if (!StringUtils.isEmpty(maxSessionFromSystemEnv)) {
            try {
                res = Integer.parseInt(maxSessionFromSystemEnv);
            } catch (NumberFormatException e) {
            }
        }

        if (res <= 0) {
            if (!StringUtils.isEmpty(maxSessionFromEnvFile)) {
                try {
                    res = Integer.parseInt(maxSessionFromEnvFile);
                } catch (NumberFormatException e) {
                }
            }
        }

        if (res <= 0) {
            if (!StringUtils.isEmpty(maxSessionFromProps)) {
                try {
                    res = Integer.parseInt(maxSessionFromProps);
                } catch (NumberFormatException e) {
                }
            }
        }
        return res;
    }

    private void initQLConfig() {
        Iterator<QLConfig> iterator = qlConfigs.iterator();
        RemoteWebDriver driver = null;
        try {
            for (MyChrome chrome : chromes.values()) {
                if (chrome.getUserTrackId() == null) {
                    driver = chrome.getWebDriver();
                    break;
                }
            }
            while (iterator.hasNext()) {
                QLConfig qlConfig = iterator.next();
                if (StringUtils.isEmpty(qlConfig.getLabel())) {
                    qlConfig.setLabel("请配置QL_LABEL_" + qlConfig.getId() + "");
                }

                boolean verify1 = !StringUtils.isEmpty(qlConfig.getQlUrl());
                boolean verify2 = verify1 && !StringUtils.isEmpty(qlConfig.getQlUsername()) && !StringUtils.isEmpty(qlConfig.getQlPassword());
                boolean verify3 = verify1 && !StringUtils.isEmpty(qlConfig.getQlClientID()) && !StringUtils.isEmpty(qlConfig.getQlClientSecret());

                boolean result_token = false;
                boolean result_usernamepassword = false;
                if (verify3) {
                    boolean success = getToken(qlConfig);
                    if (success) {
                        result_token = true;
                        qlConfig.setQlLoginType(QLConfig.QLLoginType.TOKEN);
                        jdService.fetchCurrentCKS_count(driver, qlConfig, "");
                    } else {
                        log.warn(qlConfig.getQlUrl() + "获取token失败，获取到的ck无法上传，已忽略");
                    }
                } else if (verify2) {
                    boolean result = false;
                    try {
                        result = initInnerQingLong(driver, qlConfig);
                        if (result) {
                            qlConfig.setQlLoginType(QLConfig.QLLoginType.USERNAME_PASSWORD);
                            jdService.fetchCurrentCKS_count(driver, qlConfig, "");
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    if (result) {
                        result_usernamepassword = true;
                    } else {
                        log.info("初始化青龙面板" + qlConfig.getQlUrl() + "登录失败, 获取到的ck无法上传，已忽略");
                    }
                }

                if (!result_token && !result_usernamepassword) {
                    iterator.remove();
                }
            }
        } finally {
            if (driver != null) {
                releaseWebDriver(driver.getSessionId().toString());
            }
        }

        log.info("成功添加" + qlConfigs.size() + "套配置");
    }

    public boolean getToken(QLConfig qlConfig) {
        String qlUrl = qlConfig.getQlUrl();
        String qlClientID = qlConfig.getQlClientID();
        String qlClientSecret = qlConfig.getQlClientSecret();
        try {
            ResponseEntity<String> entity = restTemplate.getForEntity(qlUrl + "/open/auth/token?client_id=" + qlClientID + "&client_secret=" + qlClientSecret, String.class);
            if (entity.getStatusCodeValue() == 200) {
                String body = entity.getBody();
                log.info("获取token " + body);
                JSONObject jsonObject = JSON.parseObject(body);
                Integer code = jsonObject.getInteger("code");
                if (code == 200) {
                    JSONObject data = jsonObject.getJSONObject("data");
                    String token = data.getString("token");
                    String tokenType = data.getString("token_type");
                    long expiration = data.getLong("expiration");
                    qlConfig.setQlToken(new QLToken(token, tokenType, expiration));
                    return true;
                }
            }
        } catch (Exception e) {
            log.error(qlUrl + "获取token失败，请检查配置");
        }
        return false;
    }

    public boolean initInnerQingLong(RemoteWebDriver webDriver, QLConfig qlConfig) {
        String qlUrl = qlConfig.getQlUrl();
        webDriver.manage().timeouts().implicitlyWait(10, TimeUnit.SECONDS).pageLoadTimeout(10, TimeUnit.SECONDS).setScriptTimeout(10, TimeUnit.SECONDS);
        try {
            String token = null;
            int retry = 0;
            while (StringUtils.isEmpty(token)) {
                retry++;
                log.info("initInnerQingLong-->" + qlConfig.getQlUrl() + "第" + retry + "尝试");
                if (retry > 2) {
                    break;
                }
                String qlUsername = qlConfig.getQlUsername();
                String qlPassword = qlConfig.getQlPassword();
                webDriver.get(qlUrl + "/login");
                log.info("initQingLong start : " + qlUrl + "/login");
                boolean b = WebDriverUtil.waitForJStoLoad(webDriver);
                if (b) {
                    webDriver.findElement(By.id("username")).sendKeys(qlUsername);
                    webDriver.findElement(By.id("password")).sendKeys(qlPassword);
                    webDriver.findElement(By.xpath("//button[@type='submit']")).click();
                    Thread.sleep(2000);
                    b = WebDriverUtil.waitForJStoLoad(webDriver);
                    if (b) {
                        RemoteExecuteMethod executeMethod = new RemoteExecuteMethod(webDriver);
                        RemoteWebStorage webStorage = new RemoteWebStorage(executeMethod);
                        LocalStorage storage = webStorage.getLocalStorage();
                        token = storage.getItem("token");
                        qlConfig.setQlToken(new QLToken(token));
                        readPassword(qlConfig);
                    }
                }
            }
        } catch (Exception e) {
            log.error(qlUrl + "测试登录失败，请检查配置");
        }
        return qlConfig.getQlToken() != null && qlConfig.getQlToken().getToken() != null;
    }

    private boolean readPassword(QLConfig qlConfig) throws IOException {
        File file = new File("/data/config/auth.json");
        if (file.exists()) {
            String s = FileUtils.readFileToString(file, "utf-8");
            JSONObject jsonObject = JSON.parseObject(s);
            String username = jsonObject.getString("username");
            String password = jsonObject.getString("password");
            if (!StringUtils.isEmpty(username) && !StringUtils.isEmpty(password) && !"adminadmin".equals(password)) {
                qlConfig.setQlUsername(username);
                qlConfig.setQlPassword(password);
                log.debug("username = " + username + ", password = " + password);
                return true;
            }
        }
        return false;
    }

    public RemoteWebDriver getDriverBySessionId(String chromeSessionId) {
        MyChrome myChromeBySessionId = getMyChromeBySessionId(chromeSessionId);
        if (myChromeBySessionId != null) {
            return myChromeBySessionId.getWebDriver();
        }
        return null;
    }

    public MyChrome getMyChromeBySessionId(String chromeSessionId) {
        if (chromes.size() > 0) {
            return chromes.get(chromeSessionId);
        }
        return null;
    }

    public synchronized MyChromeClient createNewMyChromeClient(String userTrackId, LoginType loginType, JDLoginType jdLoginType) {
        //客户端传过来的sessionid为空，可能是用户重新刷新了网页，或者新开了一个浏览器，那么就需要跟踪会话缓存来找到之前的ChromeSessionId
        MyChromeClient myChromeClient = null;
        myChromeClient = new MyChromeClient();
        myChromeClient.setLoginType(loginType);
        myChromeClient.setJdLoginType(jdLoginType);
        myChromeClient.setUserTrackId(userTrackId);
        boolean success = false;
        if (chromes.size() < CAPACITY) {
            createChrome();
        }
        for (MyChrome myChrome : chromes.values()) {
            if (myChrome.getUserTrackId() == null) {
                //双向绑定
                myChromeClient.setExpireTime(System.currentTimeMillis() + opTimeout * 1000L);
                myChromeClient.setChromeSessionId(myChrome.getChromeSessionId());
                myChrome.setUserTrackId(userTrackId);
                success = true;
                break;
            }
        }
        if (success) {
            clients.put(userTrackId, myChromeClient);
            return myChromeClient;
        } else {
            return null;
        }
    }

    public MyChromeClient getCacheMyChromeClient(String userTrackId) {
        if (userTrackId != null) {
            return clients.get(userTrackId);
        }
        return null;
    }

    @Autowired
    private ThreadPoolTaskExecutor threadPoolTaskExecutor;

    public void releaseWebDriver(String chromeSessionId) {
        Iterator<Map.Entry<String, MyChrome>> iterator = chromes.entrySet().iterator();
        while (iterator.hasNext()) {
            MyChrome myChrome = iterator.next().getValue();
            String sessionId = myChrome.getWebDriver().getSessionId().toString();
            if (sessionId.equals(chromeSessionId)) {
                try {
                    iterator.remove();
                    //获取chrome的失效时间
                    long chromeExpireTime = myChrome.getExpireTime();
                    long clientExpireTime = 0;
                    //获取客户端的失效时间
                    String userTrackId = myChrome.getUserTrackId();
                    if (userTrackId != null) {
                        MyChromeClient client = clients.get(userTrackId);
                        clientExpireTime = client.getExpireTime();
                    }
                    //chrome的存活时间不够一个opTime时间，则chrome不退出，只清理客户端引用
                    if ((chromeExpireTime - clientExpireTime) / 1000 <= opTimeout) {
                        myChrome.setUserTrackId(null);
                    } else {
                        threadPoolTaskExecutor.execute(() -> myChrome.getWebDriver().quit());
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                log.info("destroy chrome : " + sessionId);
                break;
            }
        }

        Iterator<Map.Entry<String, MyChromeClient>> iterator2 = clients.entrySet().iterator();
        while (iterator2.hasNext()) {
            MyChromeClient curr = iterator2.next().getValue();
            if (curr.getChromeSessionId().equals(chromeSessionId)) {
                try {
                    iterator2.remove();
                } catch (Exception e) {
                    e.printStackTrace();
                }
                log.info("remove MyChromeClient : " + chromeSessionId);
                break;
            }
        }
    }

    public String getXddUrl() {
        return xddUrl;
    }

    public String getXddToken() {
        return xddToken;
    }

    public List<QLConfig> getQlConfigs() {
        return qlConfigs;
    }

    public Properties getProperties() {
        return properties;
    }

    public boolean isInitSuccess() {
        return initSuccess;
    }

    @Override
    public void afterPropertiesSet() {
        init();
    }

    public <T> T exec(WebDriverOpCallBack<T> executor) {
        RemoteWebDriver webDriver = null;
        try {
            webDriver = new RemoteWebDriver(new URL(seleniumHubUrl), getOptions());
            return executor.doBusiness(webDriver);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (webDriver != null) {
                webDriver.quit();
            }
        }
        return null;
    }

    public StatClient getStatClient() {
        int availChromeCount = 0;
        int webSessionCount = 0;
        int qqSessionCount = 0;
        int totalChromeCount = CAPACITY;
        for (MyChrome chrome : chromes.values()) {
            if (chrome.getUserTrackId() == null) {
                availChromeCount++;
            } else {
                String userTrackId = chrome.getUserTrackId();
                MyChromeClient client = clients.get(userTrackId);
                if (client != null) {
                    LoginType loginType = client.getLoginType();
                    if (loginType == LoginType.WEB) {
                        webSessionCount++;
                    } else if (loginType == LoginType.QQBOT) {
                        qqSessionCount++;
                    }
                }
            }
        }
        return new StatClient(availChromeCount, webSessionCount, qqSessionCount, totalChromeCount);
    }
}
