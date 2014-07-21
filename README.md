apacheHttpClientWork
====================

Collaborative work getting auth working in my httpClient driver
This is tied to a "poor man's RPT" where we had to drive comparison work in several vendor clouds (and could not port RPT in due to licensing et al.  This is the client that is invoked, the properties, the URL list, and the script to start it.  Right now, I am starting it by invoking the script with:  ./runTCli.sh 2 6  (which says, use 2 threads and run thru the list of URLs a total of 6 times (3 times per thread in this case).  Properties includes a list of userids/passwords which are allocated across the threads.  Right now, list is 3 long, so the 2 threads take the first and second uid.  If there were 9 threads, each uid would be in use on 3 different threads.
