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
