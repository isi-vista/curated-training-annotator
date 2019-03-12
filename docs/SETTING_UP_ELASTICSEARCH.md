### Upload Dataset for index
1. Upload English Gigaword V5 to /lfs1/eng.gigaword.v5/
2. Unpack the dataset:
```
cd /lfs1/eng.gigaword.v5/
tar xvzf gigaword_eng_5_LDC2011T07.tgz
```

### Install ElasticSearch and start the service
1. Upload package of Elasticsearch to /lfs1/ElasticSearch/
```
cd /lfs1/ElasticSearch/
wget https://artifacts.elastic.co/downloads/elasticsearch/elasticsearch-6.6.1.tar.gz
```

2. Unpack the software and start the service:
```
tar xvzf elasticsearch-6.6.1.tar.gz
bash ./elasticsearch-6.6.1/bin/elasticsearch
```

3. Check service status:
``` 
curl -X GET http://localhost:9200
```
   
 It will show the elastic version and other information of the installation.

### Set up Maven
This is so we can build gigaword-indexer and we will install Maven manually:
```
wget http://apache.mirrors.lucidnetworks.net/maven/maven-3/3.6.0/binaries/apache-maven-3.6.0-bin.tar.gz
tar xzvf apache-maven-3.6.0-bin.tar.gz
```

Then add **apache-maven-3.6.0/bin** to your _PATH_

### Build gigaword-indexer
Download and install nlp-util first, which is required by gigaword-indexer:
```
git clone https://github.com/isi-nlp/nlp-util.git
cd nlp-util
mvn clean install
```

Then install gigaword-indexer:
```
git clone https://github.com/isi-vista/curated-training-annotator.git
cd curated-training-annotator
mvn clean install
```

### English Gigaword V5 Indexing
In the build of gigaword-indexer, create a parameter file **index_gigaword.gabbard.params** which has following parameters:
```
indexName: gigaword
gigawordDirectoryPath: /lfs1/eng.gigaword.v5/gigaword_eng_5
```

Then, run:
```
indexGigaword index_gigaword.gabbard.params
```

It will take a while to complete indexing for entire dataset. When indexing is done, simple test can be done to monitor the healthy of the search engine:
1. Check the index of database:
```
curl -XGET 'http://localhost:9200/_cat/indices?v'
```

2. A simple query:
```
curl -XGET http://localhost:9200/_search?q=USA
```

