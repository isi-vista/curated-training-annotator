## Setting up Inception on an ISI Virtual Machine

The directions at https://inception-project.github.io//releases/0.7.2/docs/admin-guide.html assume you are using Ubuntu, 
but our VMs run CentOS.  

This document will describe any necessary adaptations.

# Install Java

Replace the Inception documentation with the following (from https://www.digitalocean.com/community/tutorials/how-to-install-java-on-centos-and-fedora):

> go to [the Oracle Java 8 JDK Downloads Page](http://www.oracle.com/technetwork/java/javase/downloads/jdk8-downloads-2133151.html), 
> accept the license agreement, 
> and copy the download link of the appropriate Linux .rpm package (Linux x64 RPM). 
> Substitute the copied download link in place of the highlighted part of the wget command.

```
cd ~
sudo yum install wget
wget --no-cookies --no-check-certificate --header "Cookie: gpw_e24=http%3A%2F%2Fwww.oracle.com%2F; oraclelicense=accept-securebackup-cookie" "http://link_copied_from_site"
sudo yum install jdk-8u202-linux-x64.rpm
```

# Install `mysql`

```
wget http://repo.mysql.com/mysql-community-release-el7-5.noarch.rpm
sudo rpm -ivh mysql-community-release-el7-5.noarch.rpm
sudo yum update
sudo yum install mysql-server
```

Add the following to `/etc/my.cnf`:
```
character-set-server = utf8
collation-server     = utf8_bin
default-character-set = utf8
```

Then

```
service mysqld start
```

Choose to authenticate as your own user. The password it wants is your Linux password.

```
mysqladmin -u root password ‘newpassword’
sudo /sbin/chkconfig --levels 235 mysqld on
mysql -u root -p
CREATE DATABASE inception DEFAULT CHARACTER SET utf8 COLLATE utf8_bin ;
CREATE USER 'inception'@'localhost' IDENTIFIED BY 't0t4llYSecreT';
GRANT ALL PRIVILEGES ON inception.* TO 'inception'@'localhost';
FLUSH PRIVILEGES;
```

# Make a `www-data` user

At least ISI's VMs don't have it by default:

```
sudo adduser www-data
```

# Set up `Inception` directory

```
sudo mkdir /srv/inception
```

Put database settings in `/srv/inception/settings.properties`:

```
database.dialect=org.hibernate.dialect.MySQL5InnoDBDialect
database.driver=com.mysql.jdbc.Driver
database.url=jdbc:mysql://localhost:3306/inception?useSSL=false&serverTimezone=UTC&useUnicode=true&characterEncoding=UTF-8
database.username=inception
database.password=t0t4llYSecreT

# 60 * 60 * 24 * 30 = 30 days
backup.keep.time=2592000

# 60 * 5 = 5 minutes
backup.interval=300

backup.keep.number=10

external-search.enabled=true

remote-api.enabled=true
```

```
sudo chown -R www-data /srv/inception
```

# Set up Maven

This is so we can build Inception because we want to run a newer version than their last stable release.
The version of Maven installable via `yum` on CentOS is too old (as of this writing), so we install manually:
```
wget http://apache.mirrors.lucidnetworks.net/maven/maven-3/3.6.3/binaries/apache-maven-3.6.3-bin.tar.gz
tar xzvf apache-maven-3.6.3-bin.tar.gz
```

Then add `apache-maven-3.6.3/bin` to your `PATH`


# Set up git and build Inception
This is just because we want to run from the GitHub `master` branch and not a stable release.
```
sudo yum install git
# clone can be in a user home directory or wherever
git clone https://github.com/inception-project/inception.git
cd inception
mvn install -DskipTests
cp inception-app-webapp/target/inception-app-standalone-<CURRENT_VERISON_FILL_ME_IN>-SNAPSHOT.jar /srv/inception/inception.jar
sudo chown www-data:www-data /srv/inception/inception.jar
```

We skip tests because Inception has some which seem to depend on external servers which aren't always available.

Add the following to `/srv/inception/inception.conf`:
```
JAVA_OPTS="-Djava.awt.headless=true -Dinception.home=/srv/inception"
```

Then
```
sudo chown root:root /srv/inception/inception.conf
```

# Set Inception to run at startup
```
sudo ln -s /srv/inception/inception.jar /etc/init.d/inception
sudo systemctl enable inception
```


# How to sync Inception with the main repository

You will need to use `sudo` for most or all of these commands. 
From the Inception working copy's `master` branch:
```
git pull --rebase
(if you need to use another branch, check it out)
mvn install -DskipTest
cp inception-app-webapp/target/inception-app-standalone-<CURRENT_VERISON_FILL_ME_IN>-SNAPSHOT.jar /srv/inception/inception.jar
systemctl restart inception
```

We skip the tests because they are time consuming and we assume Inception's CI has already run them.

# How to inspect the Inception logs for errors

```
sudo less /var/log/inception.log
```

# How to restart Inception

```
sudo systemctl restart inception
```

# Update Inception

## Create backups
Before you begin the upgrade, you'll want to create backups in case the upgrade doesn't
go as planned. Your backup files should consist of the Inception
home directory and the SQL databases.

1. Copy the home directory (`/srv/inception`) to `/nas/gaia/curated-training/inception_backups`.
2. Create a backup of the Inception databases by running

  ```
  mysqldump -u root -p --result-file=inception_db_dump.sql --all-databases
  ```

## Update
Reminder: you may need to use `sudo` for most of these commands.
```
cd /home/gabbard/inception
checkout the branch or otherwise put the git repo in the state you want
mvn clean install -DskipTests=true
cp inception-app-webapp/target/inception-app-standalone-<CURRENT_VERISON_FILL_ME_IN>-SNAPSHOT.jar /srv/inception/inception.jar
sudo systemctl restart inception
```
You may receive a warning that `inception.service` changed on the disk. In this case, run
```
systemctl daemon-reload
systemctl restart inception
```
Restarting Inception may take a while, so it's normal for the server to be unreachable for a few
minutes. Check the log to see if anything strange is going on:

```
less /var/log/inception.log
```