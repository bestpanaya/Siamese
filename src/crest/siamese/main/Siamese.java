package crest.siamese.main;

import crest.siamese.document.JavaTerm;
import crest.siamese.helpers.*;
import crest.siamese.settings.CustomSettings;
import crest.siamese.settings.Settings;
import crest.siamese.settings.NormalizerMode;
import crest.siamese.document.Document;
import crest.siamese.document.Method;
import crest.siamese.settings.IndexSettings;
import me.xdrop.fuzzywuzzy.FuzzySearch;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.apache.lucene.index.*;
import org.apache.lucene.search.similarities.ClassicSimilarity;
import org.elasticsearch.client.Client;

import org.apache.commons.io.FileUtils;
import org.apache.lucene.store.FSDirectory;
import org.elasticsearch.client.transport.NoNodeAvailableException;

import java.io.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.UnknownHostException;
import java.nio.file.Paths;
import java.text.DateFormat;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class Siamese {

    private ESConnector[] esConnectors;
    private Client[] siameseClients = null;
    private String server;
    private String index;
    private String type;
    private String inputFolder;
    private String subInputFolder;
    private String normMode;
    private NormalizerMode modes = new NormalizerMode();
    private int ngramSize;
    private boolean isNgram;
    private boolean isPrint;
    private nGramGenerator ngen;
    private boolean isDFS;
    private String outputFolder;
    private boolean writeToFile;
    private String extension;
    private int minCloneLine;
    private int resultOffset;
    private int resultsSize;
    private int totalDocuments;
    private boolean queryReduction;
    private double qrPercentileNorm;
    private double qrPercentileOrig;
    private boolean recreateIndexIfExists;
    private String parseMode;
    private String cloneClusterFile;
    private int printEvery;
    private String command;
    private int rankingFunc;
    private String errMeasure;
    private boolean deleteIndexAfterUse;
    private String prefixToRemove;
    private String elasticsearchLoc;
    private String outputFormat;
    private String indexingMode;
    private int bulkSize;
    private int normBoost;
    private int origBoost;
    private String methodParserName;
    private String tokenizerName;
    private String normalizerName;
    private boolean multiRep;
    private boolean includeLicense;
    private String licenseExtractor;
    private String url = "none";
    private String fileLicense = "unknown";
    private boolean github = false;
    private boolean computeSimilarity = false;
    private int simThreshold = 0;
    private Tokenizer[] tokenizer;
    private Normalizer[] normalizer;
    private Tokenizer[] origTokenizer;
    private Normalizer[] origNormalizer;
    private int nThreads = 10;

    public Siamese(String configFile) {
        readFromConfigFile(configFile);
        printConfig();
        prepareTokenizers();
    }

    private void readFromConfigFile(String configFile) {
	    /* copied from
	    https://www.mkyong.com/java/java-properties-file-examples/
	     */
        Properties prop = new Properties();
        InputStream input = null;

        try {
            input = new FileInputStream(configFile);
            // load a properties file
            prop.load(input);

            // get the property value and print it out
            server = prop.getProperty("server");
            index = prop.getProperty("index");
            type = prop.getProperty("type");
            inputFolder = prop.getProperty("inputFolder");
            subInputFolder = prop.getProperty("subInputFolder");
            outputFolder = prop.getProperty("outputFolder");

            normMode = prop.getProperty("normMode");
            // set the normalisation + tokenization mode
            NormalizerMode tknzMode = new NormalizerMode();
            modes = tknzMode.setTokenizerMode(normMode.toLowerCase().toCharArray());

            isNgram = Boolean.parseBoolean(prop.getProperty("isNgram"));
            ngramSize = Integer.parseInt(prop.getProperty("ngramSize"));
            isPrint = Boolean.parseBoolean(prop.getProperty("isPrint"));
            isDFS = Boolean.parseBoolean(prop.getProperty("dfs"));
            writeToFile = Boolean.parseBoolean(prop.getProperty("writeToFile"));
            extension = prop.getProperty("extension");
            minCloneLine=Integer.parseInt(prop.getProperty("minCloneSize"));
            command = prop.getProperty("command");

            String ranking = prop.getProperty("rankingFunction");
            if (ranking.equals("tfidf"))
                rankingFunc = Settings.RankingFunction.TFIDF;
            else if (ranking.equals("bm25"))
                rankingFunc = Settings.RankingFunction.BM25;
            else if (ranking.equals("dfr"))
                rankingFunc = Settings.RankingFunction.DFR;
            else if (ranking.equals("ib"))
                rankingFunc = Settings.RankingFunction.IB;
            else if (ranking.equals("lmd"))
                rankingFunc = Settings.RankingFunction.LMD;
            else if (ranking.equals("lmj"))
                rankingFunc = Settings.RankingFunction.LMJ;

            // get the property value and print it out
            this.resultOffset = Integer.parseInt(prop.getProperty("resultOffset"));
            this.resultsSize = Integer.parseInt(prop.getProperty("resultsSize"));
            this.totalDocuments = Integer.parseInt(prop.getProperty("totalDocuments"));
            this.queryReduction = Boolean.parseBoolean(prop.getProperty("queryReduction"));
            this.qrPercentileNorm = Double.parseDouble(prop.getProperty("QRPercentileNorm"));
            this.qrPercentileOrig = Double.parseDouble(prop.getProperty("QRPercentileOrig"));
            this.normBoost = Integer.parseInt(prop.getProperty("normBoost"));
            this.origBoost = Integer.parseInt(prop.getProperty("origBoost"));

            // multi-representation
            this.multiRep = Boolean.parseBoolean(prop.getProperty("multirep"));

            // customization to support other languages
            this.methodParserName = prop.getProperty("methodParser");
            this.tokenizerName = prop.getProperty("tokenizer");
            this.normalizerName = prop.getProperty("normalizer");

            this.recreateIndexIfExists = Boolean.parseBoolean(prop.getProperty("recreateIndexIfExists"));

            String parseModeConfig = prop.getProperty("parseMode");
            if (parseModeConfig.equals("method"))
                this.parseMode = Settings.MethodParserType.METHOD;
            else
                this.parseMode = Settings.MethodParserType.FILE;

            this.printEvery = Integer.parseInt(prop.getProperty("printEvery"));
            this.cloneClusterFile = "resources/clone_clusters_" + this.parseMode + ".csv";
            String errMeasureConfig = prop.getProperty("errorMeasure");
            if (errMeasureConfig.equals("arp"))
                errMeasure = Settings.ErrorMeasure.ARP;
            else
                errMeasure = Settings.ErrorMeasure.MAP;

            deleteIndexAfterUse = Boolean.parseBoolean(prop.getProperty("deleteIndexAfterUse"));

            prefixToRemove = inputFolder;
            if (!prefixToRemove.endsWith("/"))
                prefixToRemove += "/"; // append / at the end

            elasticsearchLoc = prop.getProperty("elasticsearchLoc");
            outputFormat = prop.getProperty("outputFormat");
            indexingMode = prop.getProperty("indexingMode");
            bulkSize = Integer.parseInt(prop.getProperty("bulkSize"));

            includeLicense = Boolean.parseBoolean(prop.getProperty("license"));
            licenseExtractor = prop.getProperty("licenseExtractor");

            github = Boolean.parseBoolean(prop.getProperty("github"));

            computeSimilarity = Boolean.parseBoolean(prop.getProperty("computeSimilarity"));
            simThreshold = Integer.parseInt(prop.getProperty("simThreshold"));

            nThreads = Integer.parseInt(prop.getProperty("nThreads"));

        } catch (IOException ex) {
            ex.printStackTrace();
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void printConfig() {
        System.out.println("====== Configurations ======");
        System.out.println("server         : " + server);
        System.out.println("index          : " + index);
        System.out.println("type           : " + type);
        System.out.println("inputFolder    : " + inputFolder);
        System.out.println("outputFolder   : " + outputFolder);
        System.out.println("normalization  : " + normMode);
        System.out.println("ngramSize      : " + ngramSize);
        System.out.println("verbose        : " + isPrint);
        System.out.println("dfs            : " + isDFS);
        System.out.println("extension      : " + extension);
        System.out.println("minCloneSize   : " + minCloneLine);
        System.out.println("command        : " + command);
        System.out.println("queryReduction : " + queryReduction);
        System.out.println("multiRep       : " + multiRep);
        System.out.println("outputFormat   : " + outputFormat);
        System.out.println("indexingMode   : " + indexingMode + " (" + bulkSize + ")");
        System.out.println("============================");
    }

    private void connect(int threads) {
        esConnectors = new ESConnector[threads];
        for (int i = 0; i < esConnectors.length; i++) {
            // create a connector
            esConnectors[i] = new ESConnector(server);
        }
    }

    public void startup(int threads) {
        connect(threads);
        siameseClients = new Client[esConnectors.length];
        try {
            for (int i=0; i<esConnectors.length; i++) {
                siameseClients[i] = esConnectors[i].startup();
            }
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
    }

    public void shutdown() {
        for (ESConnector es: esConnectors) {
            es.shutdown();
        }
    }

    private void prepareTokenizers() {
        NormalizerMode tmode = new NormalizerMode();
        char[] noNormMode = {'x'};
        tmode.setTokenizerMode(noNormMode);

        origNormalizer = new Normalizer[nThreads];
        origTokenizer = new Tokenizer[nThreads];
        normalizer = new Normalizer[nThreads];
        tokenizer = new Tokenizer[nThreads];

        for (int i=0; i<origNormalizer.length; i++) {
            origNormalizer[i] = initialiseNormalizer(tmode);
            origTokenizer[i] = initialiseTokenizer(origNormalizer[i]);
            normalizer[i] = initialiseNormalizer(modes);
            tokenizer[i] = initialiseTokenizer(normalizer[i]);
        }


    }

    private OutputFormatter getOutputFormatter() {
        OutputFormatter formatter = new OutputFormatter();
        if (outputFormat.equals("csv")) {
            formatter.setFormat("csv");
            formatter.setAddStartEndLine(false);
        } else if (outputFormat.equals("csvfline")) {
            formatter.setFormat("csv");
            formatter.setAddStartEndLine(true);
        } else {
            System.out.println("ERROR: wrong output format");
            return null;
        }

        return formatter;
    }

    public String execute() throws Exception {
        // check if the client is already started up
        if (siameseClients == null) {
            startup(nThreads);
        }

        // initialise the n-gram generator
        ngen = new nGramGenerator(ngramSize);

        // default similarity function is TFIDF
        String indexSettings = IndexSettings.TFIDF.getIndexSettings(IndexSettings.TFIDF.DisCountOverlap.NO);
        String mappingStr = IndexSettings.TFIDF.mappingStr;

        if (rankingFunc == Settings.RankingFunction.TFIDF) {
            indexSettings = IndexSettings.TFIDF.getIndexSettings(IndexSettings.TFIDF.DisCountOverlap.NO);
            mappingStr = IndexSettings.TFIDF.mappingStr;
        } else if (rankingFunc == Settings.RankingFunction.BM25) {
            indexSettings = IndexSettings.BM25.getDefaultIndexSettings();
            mappingStr = IndexSettings.BM25.mappingStr;
        } else if (rankingFunc == Settings.RankingFunction.DFR) {
             indexSettings = IndexSettings.DFR.getIndexSettings(
                    IndexSettings.DFR.bmIF,
                    IndexSettings.DFR.aeL,
                    IndexSettings.DFR.normH1);
            mappingStr = IndexSettings.DFR.mappingStr;
        } else if (rankingFunc == Settings.RankingFunction.IB) {
            indexSettings = IndexSettings.IB.getIndexSettings(
                    IndexSettings.IB.distributionsLL,
                    IndexSettings.IB.lambdasDF,
                    IndexSettings.IB.normH1
            );
            mappingStr = IndexSettings.IB.mappingStr;
        } else if (rankingFunc == Settings.RankingFunction.LMD) {
            indexSettings = IndexSettings.LMD.getIndexSettings("2000");
            mappingStr = IndexSettings.LMD.mappingStr;
        } else if (rankingFunc == Settings.RankingFunction.LMJ) {
            indexSettings = IndexSettings.LMJ.getIndexSettings("0.1");
            mappingStr = IndexSettings.LMJ.mappingStr;
        }

        String outputFile = "";

        try {
            if (siameseClients != null) {
                if (github) {
                    indexGitHub();
                } else {
                    if (command.toLowerCase().equals("index")) {

                        if (recreateIndexIfExists) {
                            createIndex(indexSettings, mappingStr);
                        }

                        long startingId = 0;
                        if (!recreateIndexIfExists && doesIndexExist()) {
                            startingId = getMaxId(index) + 1;
                        }

                        long insertSize = insert(startingId);

                        if (insertSize != 0) {
                            // if ok, refresh the index, then search
                            esConnectors[0].refresh(index);
                            System.out.println("Successfully creating index.");
                        } else {
                            System.out.println("ERROR: Indexed zero file. Please check!");
                        }

                    } else if (command.toLowerCase().equals("search")) {
                        if (esConnectors[0].doesIndexExist(this.index)) {
                            OutputFormatter formatter = getOutputFormatter();
                            outputFile = search(inputFolder, resultOffset, resultsSize, queryReduction, formatter);
                        } else {
                            // index does not exist
                            throw new Exception("index " + this.index + " does not exist.");
                        }
                    }
                }
            } else {
                System.out.println("ERROR: cannot create Elasticsearch client ... ");
            }
//        } catch (NoNodeAvailableException noNodeException) {
//            throw noNodeException;
        }  catch (Exception e) {
            throw e;
        }

        shutdown();

        return outputFile;
    }

    private long getMaxId(String index) throws Exception {
        return esConnectors[0].getMaxId(index, isDFS);
    }

    private boolean doesIndexExist() {
        return esConnectors[0].doesIndexExist(index);
    }

    private boolean createIndex(String indexSettings, String mappingStr) throws NoNodeAvailableException {
        try {
            if (isPrint) System.out.println("INDEX," + index);

            // delete the index if it exists
            if (esConnectors[0].doesIndexExist(index)) {
                esConnectors[0].deleteIndex(index);
            }
            // create index
            boolean isCreated = esConnectors[0].createIndex(index, type, indexSettings, mappingStr);
            if (!isCreated) {
                System.err.println("Cannot create index: " + index);
            }
            return isCreated;
        } catch (NoNodeAvailableException e) {
            throw e;
        }
    }

    public ArrayList<EvalResult> runExperiment(
            String indexSettings,
            String mappingStr,
            String[] normModes,
            int[] ngramSizes,
            double[] dfCapNorms,
            double[] dfCapOrigs,
            String cloneClusterFilePrefix) {

        this.cloneClusterFile = "resources/" + cloneClusterFilePrefix + "_" + this.parseMode + ".csv";

        // check if the client is already started up
        if (siameseClients == null) {
            startup(nThreads);
        }

        ArrayList<EvalResult> resultSet = new ArrayList<>();

        // tries to delete the combination results of all settings if the file exist.
        // we're gonna generate this from the experiment.
        File allErrorMeasureResults = new File("all_" + this.errMeasure + ".csv");
        if (allErrorMeasureResults.exists())
            allErrorMeasureResults.delete();

        try {
            for (String normMode : normModes) {
                // reset the modes before setting it again
                modes.reset();
                // set the normalisation + tokenization mode
                NormalizerMode tknzMode = new NormalizerMode();
                modes = tknzMode.setTokenizerMode(normMode.toLowerCase().toCharArray());
                prepareTokenizers();
                String indexPrefix = this.index;
                for (int ngramSize : ngramSizes) {
                    for (double dfCapNorm: dfCapNorms) {
                        // replace the value read from the config file
                        this.qrPercentileNorm = dfCapNorm;
                        for (double dfCapOrig : dfCapOrigs) {
                            // replace the value read from the config file
                            this.qrPercentileOrig = dfCapOrig;
                            index = this.index + "_" + normMode + "_" + ngramSize + "_" + dfCapNorm + "_" + dfCapOrig;
                            if (isPrint) System.out.println("INDEX," + index);
                            // delete the index if it exists
                            if (esConnectors[0].doesIndexExist(index)) {
                                esConnectors[0].deleteIndex(index);
                            }
                            // create index
                            if (!esConnectors[0].createIndex(index, type, indexSettings, mappingStr)) {
                                System.err.println("Cannot create index: " + index);
                                System.exit(-1);
                            }
                            // initialise the ngram generator
                            ngen = new nGramGenerator(ngramSize);
                            totalDocuments = (int) insert(0);
                            if (totalDocuments != 0) {
                                // if ok, refresh the index, then search
                                esConnectors[0].refresh(index);
                                EvalResult result = evaluate(index, outputFolder, errMeasure, queryReduction, isPrint);
                                if (resultSet.size() != 0) {
                                    EvalResult bestResult = resultSet.get(0);
                                    // check for best result
                                    if (result.getValue() > bestResult.getValue()) {
                                        resultSet.set(0, result);
                                    }
                                } else {
                                    // add the first result twice since it's also the best result.
                                    resultSet.add(result);
                                }
                                // collect the result
                                resultSet.add(result);
                            } else {
                                System.out.println("Indexing error: please check!");
                            }
                            // delete index
                            if (deleteIndexAfterUse) {
                                if (!esConnectors[0].deleteIndex(index)) {
                                    System.err.println("Cannot delete index: " + index);
                                    System.exit(-1);
                                }
                            }
                            // restore index name
                            this.index = indexPrefix;

                        }
                    }
                }
            }
            shutdown();
        } catch (Exception e) {
            e.printStackTrace();
        }

        return resultSet;
    }

    @SuppressWarnings("unchecked")
    private long insert(long startingId) throws Exception {
        boolean isIndexed = true;
        ArrayList<Document> docArray = new ArrayList<>();
        ArrayList<String> origDocArray = new ArrayList<>();
        File folder = new File(inputFolder);
        // create an array of string for extensions
        String[] extensions = new String[1];
        extensions[0] = extension;
        List<File> listOfFiles = (List<File>) FileUtils.listFiles(folder, extensions, true);
        // method counter
        long count = 0;
        int fileCount = 0;
        System.out.println("Found " + listOfFiles.size() + " files.");
        for (File file : listOfFiles) {
            try {
                String license = "none";
                // TODO: find a way to consolidate this:
                // When doing CloPlag or SOCO evaluation, use this.
                // String filePath = file.getAbsolutePath().replace(prefixToRemove, "");
                // GitHub, use this.
                String filePath = file.getAbsolutePath();
                if (isPrint)
                    System.out.println(fileCount + ": " + filePath);
                fileCount++;
                // parse each file into method (if possible)
                MethodParser methodParser = initialiseMethodParser(
                        file.getAbsolutePath(),
                        prefixToRemove,
                        parseMode,
                        isPrint);
                ArrayList<Method> methodList;
                try {
                    methodList = methodParser.parseMethods();
                    // extract license (if any)
                    if (this.includeLicense) {
                        switch (this.licenseExtractor.toLowerCase()) {
                            case "ninka":
                                license = LicenseExtractor.extractLicenseWithNinka(file.getAbsolutePath()).split(";")[1];
                                break;
                            case "regexp":
                                license = methodParser.getLicense();
                                break;
                            default:
                                license = "none";
                        }

                        // level is in the file in the root, use it if cannot find localised license
                        if ((license.equals("unknown") || license.equals("none"))
                                && !this.fileLicense.equals("unknown")) {
                            license = this.fileLicense;
                        }
                    }

                    // check if there's a method
                    if (methodList.size() > 0) {
                        for (Method method : methodList) {
                            // check minimum size
                            if ((method.getEndLine() - method.getStartLine() + 1) >= minCloneLine) {
                                // Create Document object and put in an array list
                                String normSource = tokenize(method.getSrc(), tokenizer[0], isNgram);
                                String tokenizedSource = tokenize(method.getSrc(), origTokenizer[0], false);
                                String finalUrl = this.url;
                                if (!finalUrl.equals("none")) {
                                    String prefix = inputFolder;
                                    if (inputFolder.endsWith("/"))
                                        prefix = StringUtils.chop(inputFolder);
                                    finalUrl += filePath.replace(prefix, "");
                                }
                                Document d = new Document(
                                        startingId + count,
                                        filePath + "_" + method.getName(),
                                        method.getStartLine(),
                                        method.getEndLine(),
                                        normSource,
                                        tokenizedSource,
                                        method.getSrc(),
                                        license,
                                        finalUrl);
                                // add document to array
                                docArray.add(d);
                                count++;
                            }
                        }
                    }
                } catch (Exception e) {
                    System.out.println("ERROR: error while extracting methods.");
                    e.printStackTrace();
                }

                if (this.indexingMode.equals(Settings.IndexingMode.SEQUENTIAL)) {
                    try {
                        isIndexed = esConnectors[0].sequentialInsert(index, type, docArray);
                    } catch (Exception e) {
                        System.out.print(e.getMessage());
                        System.exit(0);
                    }
                    // something wrong with indexing, return false
                    if (!isIndexed)
                        throw new Exception("Cannot insert docId " + count + " in sequential mode");
                    else {
                        // reset the array list
                        docArray.clear();
                    }
                } else if (this.indexingMode.equals(Settings.IndexingMode.BULK)) {
                    // index every N docs (bulk insertion mode)
                    if (docArray.size() >= this.bulkSize) {
                        isIndexed = esConnectors[0].bulkInsert(index, type, docArray);
                        if (!isIndexed) {
                            throw new Exception("Cannot bulk insert documents");
                        }
                        else {
                            // reset the array list
                            docArray.clear();
                        }
                    }
                }

                if (fileCount % printEvery == 0) {
                    double percent = (double) fileCount * 100 / listOfFiles.size();
                    DecimalFormat df = new DecimalFormat("#.00");
                    System.out.println("Indexed " + fileCount
                            + " [" + df.format(percent) + "%] documents (" + count + " methods).");
                }
            } catch (Exception e) {
                e.printStackTrace();
                System.out.println(e.getMessage());
                System.out.println("ERROR: error while indexing a file: " + file.getAbsolutePath() + ". Skip.");
            }
        }

        // the last batch
        if (this.indexingMode.equals(Settings.IndexingMode.BULK) && docArray.size() != 0) {
            isIndexed = esConnectors[0].bulkInsert(index, type, docArray);
            if (!isIndexed)
                throw new Exception("Cannot bulk insert documents");
            else {
                // reset the array list
                docArray.clear();
            }
        }
        if (fileCount % printEvery != 0) {
            double percent = (double) fileCount * 100 / listOfFiles.size();
            DecimalFormat df = new DecimalFormat("#.00");
            System.out.println("Indexed " + fileCount
                    + " [" + df.format(percent) + "%] documents (" + count + " methods).");
        }
        // successfully indexed, return true
        System.out.println("Successfully indexed documents.");

        return count;
    }

    protected String search(
            String inputFolder,
            int offset,
            int size,
            boolean queryReduction,
            OutputFormatter formatter) throws Exception {

        String qr = "no_qr";
        if (queryReduction) {
            qr = "qr";
        }
        DateFormat df = new SimpleDateFormat("dd-MM-yy_HH-mm-S");
        Date dateobj = new Date();
        File outfile = new File(outputFolder + "/" + index + "_" + qr + "_"
                + df.format(dateobj) + ".csv");
        // if file doesn't exists, then create it
        boolean isCreated = false;
        if (!outfile.exists()) {
            isCreated = outfile.createNewFile();
        }

        if (isCreated) {
            FileWriter fw = new FileWriter(outfile.getAbsoluteFile(), true);
            BufferedWriter bw = new BufferedWriter(fw);
            // create an array of string for extensions
            String[] extensions = new String[1];
            extensions[0] = extension;
            File folder = new File(inputFolder);
            List<File> listOfFiles = (List<File>) FileUtils.listFiles(folder, extensions, true);
            System.out.println("Found: " + listOfFiles.size() + " files.");

            int count = 0;
            int methodCount = 0;
            String outToFile = "";

            for (File file : listOfFiles) {
                if (isPrint)
                    System.out.println(count + ": " + file.getAbsolutePath());
                // parse each file into methods (if possible)
                MethodParser methodParser = initialiseMethodParser(
                        file.getAbsolutePath(),
                        prefixToRemove,
                        parseMode,
                        isPrint);
                ArrayList<Method> methodList;
                String query = "";
                String origQuery = "";
                try {
                    methodList = methodParser.parseMethods();
                    String license = methodParser.getLicense();

                    // check if there's a method
//                    System.out.println("methods: " + methodList.size());
//                    System.out.println("nThreads: " + nThreads);
                    if (methodList.size() > 0) {
                        for (int i = 0; i < methodList.size(); i += nThreads) {
                            int limit = nThreads;
                            if (methodList.size() - i < nThreads) limit = methodList.size() - i;

//                            setOutputFolder("methodList.size(): " + methodList.size());
//                            System.out.println("i: " + i);
//                            System.out.println("limit: " + limit);
                            ExecutorService executor = Executors.newFixedThreadPool(nThreads);
                            List<Future<String>> list = new ArrayList<>();

                            for (int k = 0; k < limit; k++) {
                                SearchWorkerThread swt;
                                Method method = methodList.get(k);
                                // check minimum size
                                if ((method.getEndLine() - method.getStartLine() + 1) >= minCloneLine) {
//                                    System.out.println(method.getName());
                                    // search for results depending on the MR setting
                                    swt = new SearchWorkerThread(k, index, type, methodList.get(k),
                                            origBoost, normBoost, isPrint, isDFS, offset, size,
                                            formatter, license);
                                    Future<String> future = executor.submit(swt);
                                    list.add(future);
                                    methodCount ++;
                                }
                            }
                            executor.shutdown();

                            for (int l = 0; l<list.size(); l++) {
                                Future<String> r = list.get(l);
//                                System.out.println(l + " output: " + r.get());
                                outToFile += r.get() + "\n";
                            }
                        }
                    } else {
                        // check minimum size
                        if (MyUtils.countLines(file.getAbsolutePath()) >= minCloneLine) {
                            origQuery = tokenize(file, origTokenizer[0], false);
                            query = tokenize(file, tokenizer[0], isNgram);
                            ArrayList<Document> results = null;
                            // search for results depending on the MR setting
                            if (this.multiRep)
                                results = esConnectors[0].search(index, type, origQuery, query, origBoost, normBoost, isPrint, isDFS, offset, size);
                            else
                                results = esConnectors[0].search(index, type, query, isPrint, isDFS, offset, size);
                            outToFile += file.getAbsolutePath().replace(prefixToRemove, "") +
                                    "_noMethod#-1#-1#none,";
                            if (this.computeSimilarity) {
                                int[] sim = computeSimilarity(origQuery, results);
                                outToFile += formatter.format(results, sim, this.simThreshold, prefixToRemove);
                            } else {
                                outToFile += formatter.format(results, prefixToRemove);
                            }
                            outToFile += "\n";
                        }
                    }

                    count++;

                } catch (Exception e) {
                    e.printStackTrace();
                    System.out.println(e.getMessage());
//                    System.out.println("ERROR: file " + count +" generates query term size exceeds 4096 (too big).");
                }

                if (count % printEvery == 0) {
                    double percent = (double) count * 100 / listOfFiles.size();
                    DecimalFormat percentFormat = new DecimalFormat("#.00");
                    System.out.println("Searched " + count
                            + " [" + percentFormat.format(percent) + "%] documents (" + methodCount + " methods).");

                    bw.write(outToFile);
                    // reset the output to print
                    outToFile = "";
                }
            }
            // flush the last part of output
            bw.write(outToFile);
            bw.close();
            System.out.println("Searching done for " + count + " files (" + methodCount + " methods after clone size filtering).");
            System.out.println("See output at " + outfile.getAbsolutePath());
        } else {
            throw new IOException("Cannot create the output file: " + outfile.getAbsolutePath());
        }
        return outfile.getAbsolutePath();
    }

    private int[] computeSimilarity(String query, ArrayList<Document> results) {
        int[] simResults = new int[results.size()];
        for (int i=0; i<results.size(); i++) {
            Document d = results.get(i);
            int sim = FuzzySearch.tokenSetRatio(query, d.getOriginalSource());
            simResults[i] = sim;
        }
        return simResults;
    }

    private String reduceQuery(String query, String field, double limit) {
        // find the top-N rare terms in the query
        String tmpQuery = query;
        // clear the query
        query = "";
        ArrayList<JavaTerm> sortedTerms = sortTermsByFreq(index, field, tmpQuery);
        for (int i=0; i<sortedTerms.size(); i++) {
            if (sortedTerms.get(i).getFreq() <= limit)
                query += sortedTerms.get(i).getTerm() + " ";
        }
        return query;
    }


    /***
     * Evaluate the search results by either r-precision or mean average precision (MAP)
     * @param mode parameter settings
     * @param workingDir location of the results
     * @param errMeasure type of error measure
     * @return A pair of the best performance (either ARP or MAP) and its value
     */
    private EvalResult evaluate(String mode,
                                String workingDir,
                                String errMeasure,
                                boolean queryReduction,
                                boolean isPrint) throws Exception {

        // default is method-level evaluator
        Evaluator evaluator = new MethodLevelEvaluator(
                this.cloneClusterFile,
                mode,
                workingDir,
                isPrint);

        // if file-level is specified, switch to file-level evaluator
        if (parseMode.equals(Settings.MethodParserType.FILE))
            evaluator = new FileLevelEvaluator(
                    this.cloneClusterFile,
                    mode,
                    workingDir,
                    isPrint);

        // generate a search key and retrieve result size (if MAP)
        int searchKeySize = evaluator.generateSearchKey();
        EvalResult result = new EvalResult();
        String outputFile = "";

        // get the output formatter according to the settings
        OutputFormatter formatter = getOutputFormatter();

        if (errMeasure.equals(Settings.ErrorMeasure.ARP)) {
            outputFile = search(inputFolder, resultOffset, resultsSize, queryReduction, formatter);
            double arp = evaluator.evaluateARP(outputFile, resultsSize);
            if (isPrint)
                System.out.println(Settings.ErrorMeasure.ARP + ": " + arp);
            // update the max ARP value
            if (result.getValue() < arp) {
                result.setValue(arp);
                result.setSetting(outputFile);
            }
            deleteOutputFile(outputFile);
        } else if (errMeasure.equals(Settings.ErrorMeasure.MAP)) {
            outputFile = search(inputFolder, resultOffset, totalDocuments, queryReduction, formatter);
            double map = evaluator.evaluateMAP(outputFile, totalDocuments);
            if (isPrint)
                System.out.println(Settings.ErrorMeasure.MAP + ": " + map);
            // update the max MAP value
            if (result.getValue() < map) {
                result.setValue(map);
                result.setSetting(outputFile);
            }
            deleteOutputFile(outputFile);
        } else {
            System.out.println("ERROR: Invalid evaluation method.");
        }

        return result;
    }

    public double getValueAtPercentile(ArrayList<JavaTerm> termList, int percentile) {
        double[] data = new double[termList.size()];
        for (int i=0; i<termList.size(); i++) {
            data[i] = termList.get(i).getFreq();
        }
        /* copied from http://stackoverflow.com/questions/19700704/java-api-for-calculating-interquartile-range */
        DescriptiveStatistics da = new DescriptiveStatistics(data);
        return da.getPercentile(percentile);
    }

    private String tokenize(File file, Tokenizer tokenizer, boolean isNgram) throws Exception {
        String src = "";

        if (modes.getEscape() == Settings.Normalize.ESCAPE_ON) {
            try (BufferedReader br = new BufferedReader(new FileReader(file.getAbsolutePath()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    ArrayList<String> tokens = tokenizer.tokenize(escapeString(line).trim());
                    src += printArray(tokens, false);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            // generate tokens
            ArrayList<String> tokens = tokenizer.getTokensFromFile(file.getAbsolutePath());
            // enter ngram mode
            if (isNgram)
                src = printArray(ngen.generateNGramsFromJavaTokens(tokens), false);
            else
                src = printArray(tokens, false);
        }
        return src;
    }

    private String tokenize(String sourcecode, Tokenizer tokenizer, boolean isNgram) throws Exception {
        String src;
        // generate tokens
        ArrayList<String> tokens = tokenizer.getTokensFromString(sourcecode);
        // enter ngram mode
        if (isNgram)
            src = printArray(ngen.generateNGramsFromJavaTokens(tokens), false);
        else
            src = printArray(tokens, false);
        return src;
    }

    public long getIndicesStats() {
        return esConnectors[0].getIndicesStats(this.index);
    }

    public void analyseTermFreq(String indexName, String field, String freqType, String outputFileName) {
        String indexFile = elasticsearchLoc + "/data/stackoverflow/nodes/0/indices/"
                + indexName + "/0/index";
        ArrayList<JavaTerm> tokFreq = new ArrayList<>();
        ClassicSimilarity similarity = new ClassicSimilarity();

        File outputFile = new File(outputFileName);
        if (outputFile.exists()) {
            outputFile.delete();
        }

        /* adapted from
        https://stackoverflow.com/questions/28244961/lucene-4-10-2-calculate-tf-idf-for-all-terms-in-index
         */
        int count = 0;
        try {
            IndexReader reader = DirectoryReader.open(FSDirectory.open(Paths.get(indexFile)));
            int docnum = reader.numDocs();
            Fields fields = MultiFields.getFields(reader);
            Terms terms = fields.terms(field);

            TermsEnum termsEnum = terms.iterator();
            int size = 0;

            // TODO: is there a better solution?
            // iterate to get the size
            while (termsEnum.next() != null) {
                size++;
            }

            String[] termArr = new String[size];
            long[] freqArr = new long[size];

            // do the real work
            termsEnum = terms.iterator();
            while (termsEnum.next() != null) {
                String term = termsEnum.term().utf8ToString();
                long tfreq = 0;

                if (freqType.equals("tf"))
                    tfreq = termsEnum.totalTermFreq();
                else if (freqType.equals("df"))
                    tfreq = termsEnum.docFreq();
                else {
                    System.out.println("Wrong frequency. Quit!");
                    System.exit(0);
                }

                termArr[count] = term;
                freqArr[count] = tfreq;
                count++;

                if (count % 10000 == 0) {
//                    System.out.println("Processed " + count + " terms");
                }
            }
            System.out.println("Total: " + count);

            double[] data = new double[size];
            String output = "freq\n";
            for (int i = 0; i < freqArr.length; i++) {
                data[i] = freqArr[i];
                output += freqArr[i] + "\n";
                if (i > 0 && i % 10000 == 0) {
//                    System.out.println("Saving " + (i) + " terms.");
                    MyUtils.writeToFile("./",outputFileName, output, true);
                    output = "";
                }
            }
            // write the rest to the file
            MyUtils.writeToFile("./",outputFileName, output, true);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /***
     * Read idf of each term in the query directly from Lucene index
     * @param indexName name of the index
     * @param terms query containing search terms
     * @return selected top-selectionRatio terms
     */
    private ArrayList<JavaTerm> sortTermsByFreq(String indexName, String field, String terms) {

        String indexFile = elasticsearchLoc + "/data/stackoverflow/nodes/0/indices/"
                + indexName + "/0/index";
        ArrayList<JavaTerm> selectedTermsArray = new ArrayList<>();

        try {
            IndexReader reader = DirectoryReader.open(FSDirectory.open(Paths.get(indexFile)));
            String[] termsArr = terms.split(" ");
            for (String term: termsArr) {
                // TODO: get rid of the blank term (why it's blank?)
                if (!term.equals("")) {
                    Term t = new Term(field, term);
                    int freq = reader.docFreq(t);
                    JavaTerm newTerm = new JavaTerm(term, freq);
                    if (!selectedTermsArray.contains(newTerm))
                        selectedTermsArray.add(newTerm);
                }
            }

            /* copied from https://stackoverflow.com/questions/18441846/how-to-sort-an-arraylist-in-java */
            Collections.sort(selectedTermsArray);
        } catch (IOException e) {
            e.printStackTrace();
        }

        return selectedTermsArray;
    }

    private boolean deleteOutputFile(String outputFile) {
        // if set to delete the output file, delete
        if (CustomSettings.DELETE_OUTPUT_FILE) {
            File oFile = new File(outputFile);
            return oFile.delete();
        }
        return false;
    }

    private int findTP(ArrayList<String> results, String query) {
        int tp = 0;
        for (String result : results) {
            if (result.contains(query)) {
                tp++;
            }
        }
        return tp;
    }

    /***
     * Copied from: http://stackoverflow.com/questions/2808535/round-a-double-to-2-decimal-places
     */
    private double round(double value, int places) {
        if (places < 0) throw new IllegalArgumentException();

        BigDecimal bd = new BigDecimal(value);
        bd = bd.setScale(places, RoundingMode.HALF_UP);
        return bd.doubleValue();
    }

    private String printArray(ArrayList<String> arr, boolean pretty) {
        String s = "";
        for (String anArr : arr) {
            if (pretty && anArr.equals("\n")) {
                System.out.print(anArr);
                continue;
            }
            s += anArr + " ";
        }
        return s;
    }

    private String escapeString(String input) {
        String output = "";
        output += input.replace("\\", "\\\\").replace("\"", "\\\"").replace("/", "\\/").replace("\b", "\\b")
                .replace("\f", "\\f").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
        return output;
    }

    private MethodParser initialiseMethodParser(String filePath, String prefixToRemove, String mode, boolean isPrint) {
        MethodParser parser = null;
        try {
            Class cl = Class.forName(this.methodParserName);
            parser = (MethodParser) cl.newInstance();
            parser.configure(filePath, prefixToRemove, mode, isPrint);
        } catch (ClassNotFoundException|IllegalAccessException|InstantiationException e) {
            System.out.println("ERROR: could not find the specified method parser: " +
                    this.methodParserName + ". Please check if the class and package name is correct.");
        }
        return parser;
    }

    private Tokenizer initialiseTokenizer(Normalizer normalizer) {
        Tokenizer tokenizer = null;
        try {
            Class cl = Class.forName(this.tokenizerName);
            tokenizer = (Tokenizer) cl.newInstance();
            tokenizer.configure(normalizer);
        } catch (ClassNotFoundException|IllegalAccessException|InstantiationException e) {
            System.out.println("ERROR: could not find the specified tokenizer: " +
                    this.tokenizerName + ". Please check if the class and package name is correct.");
        }
        return tokenizer;
    }

    private Normalizer initialiseNormalizer(NormalizerMode modes) {
        Normalizer normalizer = null;
        try {
            Class cl = Class.forName(this.normalizerName);
            normalizer = (Normalizer) cl.newInstance();
            normalizer.configure(modes);
        } catch (ClassNotFoundException|IllegalAccessException|InstantiationException e) {
            System.out.println("ERROR: could not find the specified normalizer: " +
                    this.normalizerName + ". Please check if the class and package name is correct.");
        }
        return normalizer;
    }

    public void setIsPrint(boolean isPrint) {
        this.isPrint = isPrint;
    }

    public void setOutputFolder(String outputFolder) {
        this.outputFolder = outputFolder;
    }

    public void setNormMode(String normMode) {
        this.normMode = normMode;
    }

    public void setResultOffset(int resultOffset) {
        this.resultOffset = resultOffset;
    }

    public void setResultsSize(int resultsSize) {
        this.resultsSize = resultsSize;
    }

    public boolean getComputeSimilarity() {
        return this.computeSimilarity;
    }

    public void indexGitHub() throws Exception {
        if (this.inputFolder.endsWith("/"))
            this.inputFolder = StringUtils.chop(this.inputFolder);
        if (this.subInputFolder.endsWith("/"))
            this.subInputFolder = StringUtils.chop(this.subInputFolder);
        this.inputFolder = this.inputFolder + "/" + this.subInputFolder;
        System.out.println("Indexing: " + this.inputFolder);
        this.url = "https://github.com/" + this.subInputFolder + "/blob/master";

        File f = new File(this.inputFolder + "/LICENSE.txt");
        if (!f.exists() || f.isDirectory()) {
            f = new File(this.inputFolder + "/LICENSE");
        }

        if (f.exists() && !f.isDirectory()) {
            String[] lines = FileUtils.readFileToString(f).split("\n");
            for (String line : lines) {
                String license = LicenseExtractor.extractLicenseWithRegExp(line);
                if (!license.equals("unknown")) {
                    this.fileLicense = license;
                    break;
                }
            }
        }

        // initialise the n-gram generator
        ngen = new nGramGenerator(ngramSize);
        // default similarity function is TFIDF
        String indexSettings = IndexSettings.TFIDF.getIndexSettings(IndexSettings.TFIDF.DisCountOverlap.NO);
        String mappingStr = IndexSettings.TFIDF.mappingStr;

        try {
            if (siameseClients != null) {
                if (command.toLowerCase().equals("index")) {
                    if (recreateIndexIfExists) {
                        createIndex(indexSettings, mappingStr);
                    }
                    long startingId = 0;
                    if (!recreateIndexIfExists && doesIndexExist()) {
                        startingId = getMaxId(index) + 1;
                    }
                    long insertSize = insert(startingId);
                    if (insertSize != 0) {
                        // if ok, refresh the index, then search
                        esConnectors[0].refresh(index);
                    } else {
                        System.out.println("ERROR: Indexed zero file. Please check!");
                    }
                }
            } else {
                System.out.println("ERROR: cannot create Elasticsearch client ... ");
            }
        } catch (Exception e) {
            throw e;
        }
    }

    /***
     * A class to parallelise multiple queries
     */
    public class SearchWorkerThread implements Callable<String> {
        private int id;
        private String index;
        private String type;
        private Method method;
        private int origBoost;
        private int normBoost;
        private boolean isPrint;
        private boolean isDFS;
        private int offset;
        private int size;
        private OutputFormatter formatter;
        private String license;
        ArrayList<Document> results = new ArrayList<>();

        public SearchWorkerThread(int id,
                                  String index,
                                  String type,
                                  Method method,
                                  int origBoost,
                                  int normBoost,
                                  boolean isPrint,
                                  boolean isDFS,
                                  int offset,
                                  int size,
                                  OutputFormatter formatter,
                                  String license) {
            this.id = id;
            this.index = index;
            this.type = type;
            this.method = method;
            this.origBoost = origBoost;
            this.normBoost = normBoost;
            this.isPrint = isPrint;
            this.isDFS = isDFS;
            this.offset = offset;
            this.size = size;
            this.formatter = formatter;
            this.license = license;
        }

        private String search() throws Exception {
//            System.out.println("thread: " + this.id + " is working ...");
            String outToFile = "";
            /* TODO: fix this some time. It's weird to have a list with only a single object. */
            // write output to file
            ArrayList<Document> queryList = new ArrayList<>();
            Document q = new Document();
            q.setFile(method.getFile() + "_" + method.getName());
            q.setStartline(method.getStartLine());
            q.setEndline(method.getEndLine());
            outToFile += formatter.format(q, prefixToRemove, license) + ",";

            NormalizerMode tmode = new NormalizerMode();
            char[] noNormMode = {'x'};
            tmode.setTokenizerMode(noNormMode);
            String origQuery = tokenize(method.getSrc(), origTokenizer[this.id], false);
            String query = tokenize(method.getSrc(), tokenizer[this.id], isNgram);

            // query size limit is enforced
            if (queryReduction) {
                long docCount = getIndicesStats();
                query = reduceQuery(query, "src", qrPercentileNorm * docCount / 100);
                origQuery = reduceQuery(origQuery, "tokenizedsrc", qrPercentileOrig * docCount / 100);
            }

            // search for results depending on the MR setting
            if (multiRep)
                results = esConnectors[id].search(index, type, origQuery, query, origBoost, normBoost, isPrint, isDFS, offset, size);
            else
                results = esConnectors[id].search(index, type, query, isPrint, isDFS, offset, size);

            if (computeSimilarity) {
                int[] sim = computeSimilarity(origQuery, results);
                outToFile += formatter.format(results, sim, simThreshold, prefixToRemove);
            } else {
                outToFile += formatter.format(results, prefixToRemove);
            }
            return outToFile;
        }

        @Override
        public String toString(){
            return "Thread: " + this.id;
        }

        @Override
        public String call() throws Exception {
            String output = search();
            return output;
        }
    }
}
